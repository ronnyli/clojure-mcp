(ns clojure-mcp.tools.connection-manager.tool
  (:require [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [clojure-mcp.connection-manager :as conn-mgr]
            [clojure-mcp.core :as core]))

(defn list-connections-tool
  "Creates a tool for listing all available connections with their status.
   
   Shows port, type, status, description, and whether it's the primary/default connection."
  [server-state]
  {:name "list_connections"
   :description "List all available nREPL connections with their status, type, and description. Shows which connection is the primary/default connection."
   :schema {:type "object"
            :properties {}
            :required []}
   :tool-fn (fn [_exchange _args-map clj-result-k]
              (try
                (cond
                  ;; Connection manager format
                  (and (map? server-state) (:connection-manager server-state))
                  (let [{:keys [connection-manager]} server-state
                        connections (conn-mgr/list-connections connection-manager)
                        connection-info (mapv (fn [conn]
                                                (format "Port %d (%s): %s - %s%s"
                                                        (:port conn)
                                                        (name (:type conn))
                                                        (name (:status conn))
                                                        (:description conn)
                                                        (if (:is-primary conn) " [PRIMARY/DEFAULT]" "")))
                                              connections)
                        summary (format "\nTotal connections: %d\nDefault port: %d"
                                        (count connections)
                                        (:default-port connection-manager))]
                    (clj-result-k (conj connection-info summary) false))
                  
                  ;; Legacy single-connection format
                  (instance? clojure.lang.Atom server-state)
                  (let [client @server-state
                        port (get-in client [::core/config :port] "unknown")]
                    (clj-result-k [(format "Legacy single connection: Port %s (clojure) - connected [PRIMARY]" port)
                                   "\nTotal connections: 1"] false))
                  
                  :else
                  (clj-result-k ["Error: Invalid server state format"] true))
                
                (catch Exception e
                  (log/error e "Error listing connections")
                  (clj-result-k [(str "Error listing connections: " (.getMessage e))] true))))})

(defn connection-status-tool
  "Creates a tool for checking the health/status of connections.
   
   Can check a specific connection by port or all connections."
  [server-state]
  {:name "connection_status"
   :description "Check the health and status of nREPL connections. Optionally specify a port to check a specific connection, or omit port to check all connections."
   :schema {:type "object"
            :properties {:port {:type "integer"
                                :description "Optional port number to check specific connection"}}
            :required []}
   :tool-fn (fn [_exchange args-map clj-result-k]
              (try
                (let [port (:port args-map)]
                  (cond
                    ;; Connection manager format
                    (and (map? server-state) (:connection-manager server-state))
                    (let [{:keys [connection-manager]} server-state]
                      (if port
                        ;; Check specific connection
                        (if-let [connection (get-in connection-manager [:connections port])]
                          (let [healthy? (conn-mgr/connection-healthy? connection-manager port)
                                status-info (format "Port %d: %s - %s (%s)%s"
                                                    port
                                                    (name (:status connection))
                                                    (if healthy? "HEALTHY" "UNHEALTHY")
                                                    (:description connection)
                                                    (if (= port (:default-port connection-manager)) 
                                                      " [DEFAULT]" ""))]
                            (clj-result-k [status-info] false))
                          (clj-result-k [(format "Connection not found on port %d" port)] true))
                        
                        ;; Check all connections
                        (let [connections (:connections connection-manager)
                              status-info (mapv (fn [[p conn]]
                                                  (let [healthy? (conn-mgr/connection-healthy? connection-manager p)]
                                                    (format "Port %d: %s - %s (%s)%s"
                                                            p
                                                            (name (:status conn))
                                                            (if healthy? "HEALTHY" "UNHEALTHY")
                                                            (:description conn)
                                                            (if (= p (:default-port connection-manager)) 
                                                              " [DEFAULT]" ""))))
                                                connections)]
                          (clj-result-k status-info false))))
                    
                    ;; Legacy single-connection format
                    (instance? clojure.lang.Atom server-state)
                    (let [client @server-state
                          client-port (get-in client [::core/config :port] "unknown")]
                      (if (and port (not= port client-port))
                        (clj-result-k [(format "Connection not found on port %d (only port %s available)" port client-port)] true)
                        (clj-result-k [(format "Port %s: connected - HEALTHY [PRIMARY]" client-port)] false)))
                    
                    :else
                    (clj-result-k ["Error: Invalid server state format"] true)))
                
                (catch Exception e
                  (log/error e "Error checking connection status")
                  (clj-result-k [(str "Error checking connection status: " (.getMessage e))] true))))})

(defn switch-default-connection-tool
  "Creates a tool for switching the default connection to a different port."
  [server-state]
  {:name "switch_default_connection"
   :description "Switch the default nREPL connection to a different port. The connection must already exist."
   :schema {:type "object"
            :properties {:port {:type "integer"
                                :description "Port number of the connection to set as default"}}
            :required ["port"]}
   :tool-fn (fn [_exchange args-map clj-result-k]
              (try
                (let [port (:port args-map)]
                  (cond
                    ;; Connection manager format
                    (and (map? server-state) (:connection-manager server-state))
                    (let [connection-manager (:connection-manager server-state)]
                      (if (get-in connection-manager [:connections port])
                        (do
                          ;; Update the connection manager (this would need to be mutable or returned)
                          ;; For now, just report what would happen
                          (clj-result-k [(format "Would switch default connection to port %d" port)
                                         "Note: This is a read-only implementation. Connection manager would need to be mutable for full functionality."] false))
                        (clj-result-k [(format "Connection not found on port %d" port)] true)))
                    
                    ;; Legacy single-connection format
                    (instance? clojure.lang.Atom server-state)
                    (clj-result-k ["Cannot switch default connection in legacy single-connection mode"] true)
                    
                    :else
                    (clj-result-k ["Error: Invalid server state format"] true)))
                
                (catch Exception e
                  (log/error e "Error switching default connection")
                  (clj-result-k [(str "Error switching default connection: " (.getMessage e))] true))))})

(defn add-connection-tool
  "Creates a tool for dynamically adding new connections at runtime.
   
   Only available when using connection manager (not legacy mode)."
  [server-state]
  {:name "add_connection"
   :description "Dynamically add a new nREPL connection. Requires connection manager mode (not available in legacy single-connection mode)."
   :schema {:type "object"
            :properties {:port {:type "integer"
                                :description "Port number for the new connection"}
                         :host {:type "string"
                                :description "Host for the connection (default: localhost)"}
                         :type {:type "string"
                                :enum ["clojure" "cljs" "clr"]
                                :description "Type of connection (default: clojure)"}
                         :description {:type "string"
                                       :description "Human-readable description for the connection"}}
            :required ["port"]}
   :tool-fn (fn [_exchange args-map clj-result-k]
              (try
                (cond
                  ;; Connection manager format
                  (and (map? server-state) (:connection-manager server-state))
                  (let [{:keys [connection-manager primary-client-atom]} server-state
                        port (:port args-map)
                        host (or (:host args-map) "localhost")
                        conn-type (keyword (or (:type args-map) "clojure"))
                        description (or (:description args-map) 
                                        (format "%s REPL on port %d" (name conn-type) port))
                        config {:port port
                                :host host
                                :type conn-type
                                :description description}]
                    
                    (if (get-in connection-manager [:connections port])
                      (clj-result-k [(format "Connection already exists on port %d" port)] true)
                      (do
                        (clj-result-k [(format "Adding connection: Port %d (%s) - %s" 
                                               port (name conn-type) description)]
                                      false)
                        (conn-mgr/add-connection-to-manager connection-manager primary-client-atom config core/create-additional-connection))))
                  
                  ;; Legacy single-connection format
                  (instance? clojure.lang.Atom server-state)
                  (clj-result-k ["Cannot add connections in legacy single-connection mode"
                                 "Use multi-connection configuration to enable dynamic connection management"] true)
                  
                  :else
                  (clj-result-k ["Error: Invalid server state format"] true))
                
                (catch Exception e
                  (log/error e "Error adding connection")
                  (clj-result-k [(str "Error adding connection: " (.getMessage e))] true))))})

(defn remove-connection-tool
  "Creates a tool for removing connections at runtime.
   
   Cannot remove the primary connection for safety."
  [server-state]
  {:name "remove_connection"
   :description "Remove an nREPL connection by port. Cannot remove the primary/default connection for safety."
   :schema {:type "object"
            :properties {:port {:type "integer"
                                :description "Port number of the connection to remove"}}
            :required ["port"]}
   :tool-fn (fn [_exchange args-map clj-result-k]
              (try
                (let [port (:port args-map)]
                  (cond
                    ;; Connection manager format
                    (and (map? server-state) (:connection-manager server-state))
                    (let [connection-manager (:connection-manager server-state)
                          default-port (:default-port connection-manager)]
                      (cond
                        (= port default-port)
                        (clj-result-k [(format "Cannot remove primary/default connection on port %d" port)] true)
                        
                        (not (get-in connection-manager [:connections port]))
                        (clj-result-k [(format "Connection not found on port %d" port)] true)
                        
                        :else
                        ;; This is a read-only implementation
                        (clj-result-k [(format "Would remove connection on port %d" port)
                                       "Note: This is a read-only implementation. Full functionality requires mutable connection manager."] false)))
                    
                    ;; Legacy single-connection format
                    (instance? clojure.lang.Atom server-state)
                    (clj-result-k ["Cannot remove connections in legacy single-connection mode"] true)
                    
                    :else
                    (clj-result-k ["Error: Invalid server state format"] true)))
                
                (catch Exception e
                  (log/error e "Error removing connection")
                  (clj-result-k [(str "Error removing connection: " (.getMessage e))] true))))})

(defn get-connection-manager-tools
  "Returns all connection manager tools.
   
   These tools provide runtime management of multiple nREPL connections."
  [server-state]
  [(list-connections-tool server-state)
   (connection-status-tool server-state)
   (switch-default-connection-tool server-state)
   (add-connection-tool server-state)
   (remove-connection-tool server-state)]) 