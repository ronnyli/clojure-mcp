(ns clojure-mcp.config
  (:require
   [clojure.java.io :as io]
   [clojure-mcp.nrepl :as nrepl]
   [clojure.edn :as edn]
   [clojure.tools.logging :as log]))

(defn- relative-to [dir path]
  (try 
    (let [f (io/file path)]
      (if (.isAbsolute f)
        (.getCanonicalPath f)
        (.getCanonicalPath (io/file dir path))))
    (catch Exception e
      (log/warn "Bad file paths " (pr-str [dir path]))
      nil)))

(defn process-remote-config [{:keys [allowed-directories emacs-notify] :as config} user-dir]
  (let [ud (io/file user-dir)]
    (assert (and (.isAbsolute ud)
                 (.isDirectory ud)))
    (cond-> config
      user-dir (assoc :nrepl-user-dir (.getCanonicalPath ud))
      true
      (assoc :allowed-directories
             (->> (cons user-dir allowed-directories)
                  (keep #(relative-to user-dir %))
                  distinct
                  vec))
      (some? (:emacs-notify config))
      (assoc :emacs-notify (boolean (:emacs-notify config))))))

(defn load-remote-config [nrepl-client user-dir]
  (let [remote-cfg-str
        (nrepl/tool-eval-code
         nrepl-client
         (pr-str
          '(do
             (require '[clojure.java.io :as io])
             (if-let [f (clojure.java.io/file "." ".clojure-mcp" "config.edn")]
               (when (.exists f) (clojure.edn/read-string (slurp f)))))))
        remote-config (try (edn/read-string remote-cfg-str)
                           (catch Exception _ {}))
        processed-config (process-remote-config remote-config user-dir)]
    (log/info "Loaded remote-config:" remote-config)
    (log/info "Processed config:" processed-config)
    processed-config))

(defn get-config [nrepl-client-map k]
  (get-in nrepl-client-map [::config k]))

(defn get-allowed-directories [nrepl-client-map]
  (get-config nrepl-client-map :allowed-directories))

(defn get-emacs-notify [nrepl-client-map]
  (get-config nrepl-client-map :emacs-notify))

(defn get-nrepl-user-dir [nrepl-client-map]
  (get-config nrepl-client-map :nrepl-user-dir))

(defn set-config! [nrepl-client-atom k v]
  (swap! nrepl-client-atom assoc-in [::config k] v))

;; Multi-connection configuration support

(defn validate-connection-config
  "Validates a connection configuration map.
   
   Ensures required fields are present and ports are unique.
   Returns [valid? error-message]"
  [config]
  (cond
    (not (map? config))
    [false "Config must be a map"]
    
    (not (:port config))
    [false "Connection config must include :port"]
    
    (not (integer? (:port config)))
    [false "Port must be an integer"]
    
    (not (pos? (:port config)))
    [false "Port must be positive"]
    
    :else
    [true nil]))

(defn validate-multi-connection-config
  "Validates a multi-connection configuration.
   
   Ensures all connections have valid configs and unique ports.
   Returns [valid? error-message]"
  [connections]
  (cond
    (not (map? connections))
    [false "Connections must be a map"]
    
    (empty? connections)
    [false "At least one connection must be specified"]
    
    :else
    (let [ports (keys connections)
          port-configs (vals connections)]
      (cond
        (not (every? integer? ports))
        [false "All connection keys must be port numbers (integers)"]
        
        (not (apply distinct? ports))
        [false "All ports must be unique"]
        
        :else
        (let [validation-results (map validate-connection-config port-configs)
              invalid-configs (filter #(not (first %)) validation-results)]
          (if (empty? invalid-configs)
            [true nil]
            [false (str "Invalid connection configs: " 
                       (clojure.string/join ", " (map second invalid-configs)))]))))))

(defn is-legacy-single-connection-config?
  "Checks if a config is the legacy single-connection format.
   
   Returns true if config has :port but not :connections."
  [config]
  (and (map? config)
       (:port config)
       (not (:connections config))))

(defn is-shadow-config?
  "Checks if a config uses the Shadow ClojureScript pattern.
   
   Returns true if config has both :port and :shadow-port."
  [config]
  (and (map? config)
       (:port config)
       (:shadow-port config)))

(defn is-multi-connection-config?
  "Checks if a config is the new multi-connection format.
   
   Returns true if config has :connections key."
  [config]
  (and (map? config)
       (:connections config)))

(defn migrate-legacy-config
  "Converts a legacy single-connection config to multi-connection format.
   
   Takes a config with :port and converts to {:connections {port {...}}}
   Preserves all other configuration keys."
  [config]
  (let [port (:port config)
        connection-config (-> config
                            (dissoc :port)
                            (assoc :port port
                                   :type :clojure
                                   :default true
                                   :description "Migrated from legacy config"))]
    {:connections {port connection-config}}))

(defn preserve-shadow-config-pattern
  "Converts Shadow config pattern to multi-connection format.
   
   Takes a config with :port and :shadow-port and converts to multi-connection format.
   Preserves Shadow-specific keys for the ClojureScript connection."
  [config]
  (let [clj-port (:port config)
        cljs-port (:shadow-port config)
        shadow-build (:shadow-build config)
        shadow-watch (:shadow-watch config)
        
        clj-config (-> config
                     (dissoc :port :shadow-port :shadow-build :shadow-watch)
                     (assoc :port clj-port
                            :type :clojure
                            :default true
                            :description "Primary Clojure REPL"))
        
        cljs-config {:port cljs-port
                    :type :cljs
                    :description "Shadow ClojureScript REPL"
                    :shadow-build shadow-build
                    :shadow-watch shadow-watch}]
    
    {:connections {clj-port clj-config
                   cljs-port cljs-config}
     ;; Preserve Shadow-specific keys at top level for backward compatibility
     :shadow-build shadow-build
     :shadow-port cljs-port}))

(defn parse-multi-connection-config
  "Parses a configuration and normalizes it to multi-connection format.
   
   Handles:
   - Legacy single-connection: {:port 7888} 
   - Shadow pattern: {:port 7888 :shadow-port 7889 :shadow-build \"app\"}
   - Multi-connection: {:connections {7888 {...} 7889 {...}}}
   
   Returns normalized config in multi-connection format or throws on invalid config."
  [config]
  (log/info "Parsing connection config:" config)
  
  (cond
    (is-multi-connection-config? config)
    (do
      (log/info "Detected multi-connection config")
      (let [[valid? error] (validate-multi-connection-config (:connections config))]
        (if valid?
          config
          (throw (ex-info "Invalid multi-connection config" {:error error :config config})))))
    
    (is-shadow-config? config)
    (do
      (log/info "Detected Shadow config pattern, converting to multi-connection format")
      (preserve-shadow-config-pattern config))
    
    (is-legacy-single-connection-config? config)
    (do
      (log/info "Detected legacy single-connection config, migrating to multi-connection format")
      (migrate-legacy-config config))
    
    :else
    (throw (ex-info "Unrecognized config format" {:config config}))))

(defn get-primary-connection-config
  "Gets the primary connection config from a normalized multi-connection config.
   
   Returns the connection config marked as default, or the first one if none marked."
  [normalized-config]
  (let [connections (:connections normalized-config)
        default-connection (first (filter #(:default (second %)) connections))
        first-connection (first connections)]
    (second (or default-connection first-connection))))

(defn get-additional-connections-config
  "Gets additional (non-primary) connection configs from a normalized multi-connection config.
   
   Returns a map of {port -> connection-config} for non-primary connections."
  [normalized-config]
  (let [connections (:connections normalized-config)
        primary-config (get-primary-connection-config normalized-config)
        primary-port (:port primary-config)]
    (into {} (filter #(not= primary-port (first %)) connections))))

