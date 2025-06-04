(ns clojure-mcp.connection-manager
  "Connection manager for handling multiple nREPL connections."
  (:require [clojure.tools.logging :as log]
            [clojure-mcp.nrepl :as nrepl]))

(defn create-connection-manager
  "Creates a connection manager with a primary connection.

   Returns a connection manager that looks like
   {:connections {port {:client-map primary-client-map
                        :port port
                        :default true
                        :description \"Stagehand staging connection\"}}}"
  []
  {:connections {}})

(defn add-connection-to-manager
  "Adds a new connection to the connection manager using the existing create-additional-connection function.
   
   Parameters:
   - connection-manager: The connection manager
   - primary-client-atom: The primary client atom (for copying configuration)
   - connection-config: Configuration for the new connection
   - create-additional-connection-fn: Function to create additional connections
   
   Returns updated connection manager."
  [connection-manager connection-config create-additional-connection-fn]
  (let [port (:port connection-config)
        conn-type (:type connection-config :clojure)
        default? (:default connection-config false)
        description (:description connection-config "Additional connection")]
    
    (log/info "Adding connection to manager:" port conn-type)
    
    (try
      ;; Use the provided create-additional-connection function
      (let [new-client-map (create-additional-connection-fn connection-config)
            connection-entry {:client-map new-client-map
                             :port port
                             :status :connected
                             :type conn-type
                             :default default?
                             :description description}]
        
        (-> connection-manager
            (assoc-in [:connections port] connection-entry)
            (cond-> default? (assoc :default-port port))))
      
      (catch Exception e
        (log/error e "Failed to add connection on port:" port)
        (let [error-entry {:port port
                          :status :error
                          :type conn-type
                          :default default?
                          :description description
                          :error (.getMessage e)}]
          (assoc-in connection-manager [:connections port] error-entry))))))

(defn remove-connection-from-manager
  "Removes a connection from the connection manager.
   
   Parameters:
   - connection-manager: The connection manager
   - port: Port number of the connection to remove
   
   Returns updated connection manager."
  [connection-manager port]
  (let [connection (get-in connection-manager [:connections port])]
    (when (and connection (= :connected (:status connection)))
      (try
        ;; Stop polling on the connection
        (when-let [client-map (:client-map connection)]
          (nrepl/stop-polling client-map))
        (catch Exception e
          (log/error e "Error stopping connection on port:" port))))
    
    ;; Remove from connections and update default if necessary
    (-> connection-manager
        (update :connections dissoc port)
        (cond-> (= port (:default-port connection-manager))
          (assoc :default-port (-> connection-manager :connections keys first))))))

(defn get-connection-by-port
  "Gets a connection by port number.
   
   Parameters:
   - connection-manager: The connection manager
   - port: Port number
   
   Returns the nrepl-client-map or nil if not found."
  [connection-manager port]
  (when-let [connection (get-in connection-manager [:connections port])]
    (when (= :connected (:status connection))
      (:client-map connection))))

(defn get-connection-by-type
  "Gets the first connection of a specific type.
   
   Parameters:
   - connection-manager: The connection manager
   - conn-type: Connection type keyword (:clojure, :cljs, etc.)
   
   Returns the nrepl-client-map or nil if not found."
  [connection-manager conn-type]
  (->> (:connections connection-manager)
       vals
       (filter #(and (= conn-type (:type %))
                    (= :connected (:status %))))
       first
       :client-map))

(defn get-default-connection
  "Gets the default connection from the connection manager.
   
   Parameters:
   - connection-manager: The connection manager
   
   Returns the nrepl-client-map for the default connection."
  [connection-manager]
  (get-connection-by-port connection-manager (:default-port connection-manager)))

(defn list-connections
  "Lists all connections in the connection manager.
   
   Parameters:
   - connection-manager: The connection manager
   
   Returns a vector of connection info maps."
  [connection-manager]
  (->> (:connections connection-manager)
       (map (fn [[port connection]]
              (-> connection
                  (select-keys [:port :status :type :default :description :error])
                  (assoc :port port))))
       (sort-by :port)
       vec))

(defn set-default-connection
  "Sets the default connection port.
   
   Parameters:
   - connection-manager: The connection manager
   - port: Port number to set as default
   
   Returns updated connection manager or nil if port doesn't exist."
  [connection-manager port]
  (if (get-in connection-manager [:connections port])
    (-> connection-manager
        ;; Remove default flag from current default
        (update-in [:connections (:default-port connection-manager) :default] (constantly false))
        ;; Set new default
        (assoc :default-port port)
        (assoc-in [:connections port :default] true))
    (do
      (log/warn "Cannot set default to non-existent port:" port)
      connection-manager)))

(defn connection-healthy?
  "Checks if a connection is healthy.
   
   Parameters:
   - connection-manager: The connection manager
   - port: Port number to check
   
   Returns true if connection is healthy, false otherwise."
  [connection-manager port]
  (= :connected (get-in connection-manager [:connections port :status])))

(defn get-connection-status
  "Gets detailed status information for a connection.
   
   Parameters:
   - connection-manager: The connection manager
   - port: Port number
   
   Returns status map with connection details."
  [connection-manager port]
  (if-let [connection (get-in connection-manager [:connections port])]
    (-> connection
        (select-keys [:port :status :type :default :description :error])
        (assoc :port port))
    {:port port :status :not-found})) 