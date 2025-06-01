(ns clojure-mcp.core
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure-mcp.nrepl :as nrepl]
            [clojure-mcp.config :as config]
            [clojure-mcp.connection-manager :as conn-mgr])
  
  (:import [io.modelcontextprotocol.server.transport
            StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer McpServerFeatures
            McpServerFeatures$AsyncToolSpecification
            McpServerFeatures$AsyncResourceSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities
            McpSchema$Tool
            McpSchema$CallToolResult
            McpSchema$TextContent
            McpSchema$Prompt
            McpSchema$PromptArgument
            McpSchema$GetPromptRequest
            McpSchema$GetPromptResult
            McpSchema$PromptMessage
            McpSchema$Role
            McpSchema$LoggingLevel
            McpSchema$Resource
            McpSchema$ReadResourceResult
            McpSchema$TextResourceContents
            McpSchema$ResourceContents]
           [io.modelcontextprotocol.server McpServer McpServerFeatures
            McpServerFeatures$AsyncToolSpecification
            McpServerFeatures$AsyncPromptSpecification]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]))

(defn create-mono-from-callback
  "Creates a function that takes the exchange and the arguments map and
  returns a Mono promise The callback function should take three
  arguments: 
   - exchange: The MCP exchange object 
   - arguments: The arguments map sent in the request 
   - continuation: A function that will be called with the result and will fullfill the promise"
  [callback-fn]
  (fn [exchange arguments]
    (Mono/create
     (reify java.util.function.Consumer
       (accept [this sink]
         (callback-fn
          exchange
          arguments
          (fn [result]
            (.success sink result))))))))

(defn ^McpSchema$CallToolResult adapt-result [list-str error?]
  (McpSchema$CallToolResult.
   (mapv #(McpSchema$TextContent. %) list-str)
   error?))

(defn create-async-tool
  "Creates an AsyncToolSpecification with the given parameters.
   
   Takes a map with the following keys:
    :name         - The name of the tool
    :description  - A description of what the tool does
    :schema       - JSON schema for the tool's input parameters
    :service-atom - The atom holding the nREPL client connection.
    :tool-fn      - Function that implements the tool's logic.
                    Signature: (fn [exchange args-map nrepl-client clj-result-k] ... )
                      * exchange     - ignored (or used for advanced features)
                      * arg-map      - map with string keys representing the mcp tool call args
                      * nrepl-client - the validated and dereferenced nREPL client
                      * clj-result-k - continuation fn taking vector of strings and boolean error flag."
  [{:keys [name description schema tool-fn]}]
  (let [schema-json (json/write-str schema)
        mono-fn (create-mono-from-callback
                 (fn [exchange arg-map mono-fill-k]
                   (let [clj-result-k
                         (fn [res-list error?]
                           (mono-fill-k (adapt-result res-list error?)))]
                     (tool-fn exchange arg-map clj-result-k))))]
    (McpServerFeatures$AsyncToolSpecification.
     (McpSchema$Tool. name description schema-json)
     (reify java.util.function.BiFunction
       (apply [this exchange arguments]
         (mono-fn exchange arguments))))))

(defn ^McpSchema$GetPromptResult adapt-prompt-result
  "Adapts a Clojure prompt result map into an McpSchema$GetPromptResult.
   Expects a map like {:description \"...\" :messages [{:role :user :content \"...\"}]}"
  [{:keys [description messages]}]
  (let [mcp-messages (mapv (fn [{:keys [role content]}]
                             (McpSchema$PromptMessage.
                              (case role ;; Convert keyword role to McpSchema$Role enum
                                ;; :system McpSchema$Role/SYSTEM
                                :user McpSchema$Role/USER
                                :assistant McpSchema$Role/ASSISTANT
                                ;; Add other roles if needed
                                )
                              (McpSchema$TextContent. content))) ;; Assuming TextContent for now
                           messages)]
    (McpSchema$GetPromptResult. description mcp-messages)))

(defn create-async-prompt
  "Creates an AsyncPromptSpecification with the given parameters.
   
   Takes a map with the following keys:
    :name        - The name (ID) of the prompt
    :description - A description of the prompt
    :arguments   - A vector of maps, each defining an argument:
                   {:name \"arg-name\" :description \"...\" :required? true/false}
    :prompt-fn   - Function that implements the prompt logic.
                   Signature: (fn [exchange request-args clj-result-k] ... )
                     * exchange - The MCP exchange object
                     * request-args - Map of arguments provided in the client request
                     * clj-result-k - Continuation fn taking one map argument:
                                      {:description \"...\" :messages [{:role :user :content \"...\"}]} "
  [{:keys [name description arguments prompt-fn]}]
  (let [mcp-args (mapv (fn [{:keys [name description required?]}]
                         (McpSchema$PromptArgument. name description required?))
                       arguments)
        mcp-prompt (McpSchema$Prompt. name description mcp-args)
        mono-fn (create-mono-from-callback ;; Reuse the existing helper
                 (fn [_ request mono-fill-k]
                   ;; The request object has an .arguments() method
                   (let [request-args (.arguments ^McpSchema$GetPromptRequest request)] ;; <-- Corrected method call
                     (prompt-fn _ request-args
                                (fn [clj-result-map]
                                  (mono-fill-k (adapt-prompt-result clj-result-map)))))))]
    (McpServerFeatures$AsyncPromptSpecification.
     mcp-prompt
     (reify java.util.function.BiFunction
       (apply [this exchange request]
         (mono-fn exchange request))))))

(defn add-tool
  "Helper function to create an async tool from a map and add it to the server."
  [mcp-server tool-map]
  (.removeTool mcp-server (:name tool-map))
  ;; Pass the service-atom along when creating the tool
  (-> (.addTool mcp-server (create-async-tool tool-map))
      (.subscribe)))

(defn create-async-resource
  "Creates an AsyncResourceSpecification with the given parameters.
   
   Takes a map with the following keys:
    :url          - The URL of the resource
    :name         - The name of the resource
    :description  - A description of what the resource is
    :mime-type    - The MIME type of the resource
    :resource-fn  - Function that implements the resource retrieval logic.
                    Signature: (fn [exchange request clj-result-k] ... )
                      * exchange     - The MCP exchange object
                      * request      - The request object
                      * clj-result-k - continuation fn taking a vector of strings"
  [{:keys [url name description mime-type resource-fn]}]
  (let [resource (McpSchema$Resource. url name description mime-type nil)
        mono-fn (create-mono-from-callback
                 (fn [exchange request mono-fill-k]
                   (resource-fn
                    exchange
                    request
                    (fn [result-strings]
                      ;; Create TextResourceContents objects with the URL and MIME type
                      (let [resource-contents (mapv #(McpSchema$TextResourceContents. url mime-type %)
                                                    result-strings)]
                        ;; Create ReadResourceResult with the list of TextResourceContents
                        (mono-fill-k (McpSchema$ReadResourceResult. resource-contents)))))))]
    (McpServerFeatures$AsyncResourceSpecification.
     resource
     (reify java.util.function.BiFunction
       (apply [this exchange request]
         (mono-fn exchange request))))))

(defn add-resource
  "Helper function to create an async resource from a map and add it to the server.
   
   Takes an MCP server and a resource map with:
    :url          - The URL of the resource
    :name         - The name of the resource
    :description  - A description of what the resource is
    :mime-type    - The MIME type of the resource
    :resource-fn  - Function that implements the resource retrieval logic."
  [mcp-server resource-map]
  (.removeResource mcp-server (:url resource-map))
  (-> (.addResource mcp-server (create-async-resource resource-map))
      (.subscribe)))

(defn add-prompt
  "Helper function to create an async prompt from a map and add it to the server.
   
   Takes an MCP server and a prompt map with:
    :name        - The name (ID) of the prompt
    :description - A description of the prompt
    :arguments   - A vector of maps, each defining an argument
    :prompt-fn   - Function that implements the prompt logic."
  [mcp-server prompt-map]
  (.removePrompt mcp-server (:name prompt-map))
  (-> (.addPrompt mcp-server (create-async-prompt prompt-map))
      (.subscribe)))

;; helper tool to demonstrate how all this gets hooked together

(defn mcp-server
  "Creates a basic stdio mcp server"
  []
  (log/info "Starting MCP server")
  (try
    (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
          server (-> (McpServer/async transport-provider)
                     (.serverInfo "clojure-server" "0.1.0")
                     (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                        (.tools true)
                                        (.prompts true)
                                        (.resources true true) ;; resources method takes two boolean parameters
                                        #_(.logging)
                                        (.build)))
                     (.build))]

      (log/info "MCP server initialized successfully")
      server)
    (catch Exception e
      (log/error e "Failed to initialize MCP server")
      (throw e))))

(defn create-and-start-nrepl-connection
  "Convenience higher-level API function to create and initialize an nREPL connection.
   
   This function handles the complete setup process including:
   - Creating the nREPL client connection
   - Starting the polling mechanism
   - Loading required namespaces and helpers
   - Setting up the working directory
   - Loading remote configuration
   
   Takes initial-config map with :port and optional :host.
   Returns the configured nrepl-client-map with ::config/config attached."
  [initial-config]
  (log/info "Creating nREPL connection with config:" initial-config)
  (try
    (let [nrepl-client-map (nrepl/create initial-config)]
      (log/info "nREPL client map created")
      (nrepl/start-polling nrepl-client-map)
      (log/info "Started polling nREPL")

      (log/debug "Loading necessary namespaces and helpers")
      (nrepl/eval-code nrepl-client-map
                       (str
                        "(require 'clojure.repl)"
                        "(require 'nrepl.util.print)")
                       identity)
      (nrepl/tool-eval-code nrepl-client-map (slurp (io/resource "clojure-mcp/repl_helpers.clj")))
      (nrepl/tool-eval-code nrepl-client-map "(in-ns 'user)")
      (log/debug "Required namespaces loaded")

      (let [user-dir (try
                       (edn/read-string
                        (nrepl/tool-eval-code
                         nrepl-client-map
                         "(System/getProperty \"user.dir\")"))
                       (catch Exception e
                         (log/warn e "Failed to get user.dir")
                         nil))]
        (if user-dir
          (log/info "Working directory set to:" user-dir)
          (do
            (log/warn "Could not determine working directory")
            (throw (ex-info "No user directory!!" {}))))
        (assoc nrepl-client-map
               ::config/config
               (config/load-remote-config nrepl-client-map user-dir))))
    (catch Exception e
      (log/error e "Failed to create nREPL connection")
      (throw e))))

(defn create-additional-connection
  ([nrepl-client-atom initial-config]
   (create-additional-connection nrepl-client-atom initial-config identity))
  ([nrepl-client-atom initial-config initialize-fn]
   (log/info "Creating additional nREPL connection" initial-config)
   (try
     (let [nrepl-client-map (nrepl/create initial-config)]
       (nrepl/start-polling nrepl-client-map)
       ;; copy config
       ;; maybe we should create this just like the normal nrelp connection?
       ;; we should introspect the project and get a working directory
       ;; and maybe add it to allowed directories for both
       (when initialize-fn (initialize-fn nrepl-client-map))
       (assert (::config/config @nrepl-client-atom))
       ;; copy config over for now
       (assoc nrepl-client-map ::config/config (::config/config @nrepl-client-atom)))
     (catch Exception e
       (log/error e "Failed to create additional nREPL connection")
       (throw e)))))

(defn close-servers
  "Convenience higher-level API function to gracefully shut down MCP and nREPL servers.
   
   This function handles the complete shutdown process including:
   - Stopping nREPL polling if a client exists in nrepl-client-atom
   - Gracefully closing the MCP server
   - Proper error handling and logging"
  [nrepl-client-atom]
  (log/info "Shutting down servers")
  (try
    (when-let [client @nrepl-client-atom]
      (log/info "Stopping nREPL polling")
      (nrepl/stop-polling client)
      (log/info "Closing MCP server gracefully")
      (.closeGracefully (:mcp-server client))
      (log/info "Servers shut down successfully"))
    (catch Exception e
      (log/error e "Error during server shutdown")
      (throw e))))

;; Connection Manager Integration Functions

(defn create-connection-manager-from-config
  "Creates a connection manager from a configuration.
   
   Handles both single-connection and multi-connection configurations.
   Returns a map containing the connection manager and primary client atom.
   
   For backward compatibility:
   - Single-connection configs create a manager with one connection
   - Multi-connection configs create a manager with all specified connections
   - Shadow configs preserve existing Shadow integration patterns
   
   Returns: {:connection-manager <manager> :primary-client-atom <atom>}"
  [config]
  (log/info "Creating connection manager from config")
  (let [normalized-config (config/parse-multi-connection-config config)
        primary-config (config/get-primary-connection-config normalized-config)
        additional-configs (config/get-additional-connections-config normalized-config)]
    
    (log/info "Primary connection config:" primary-config)
    (log/info "Additional connections:" (keys additional-configs))
    
    ;; Create primary connection using existing function
    (let [primary-client-map (create-and-start-nrepl-connection primary-config)
          primary-client-atom (atom primary-client-map)
          
          ;; Create connection manager with primary connection
          connection-manager (conn-mgr/create-connection-manager primary-client-map)]
      
      ;; Add additional connections if any
      (let [final-manager 
            (reduce (fn [manager [port conn-config]]
                      (log/info "Adding additional connection on port:" port)
                      (conn-mgr/add-connection-to-manager 
                       manager 
                       primary-client-atom 
                       conn-config
                       create-additional-connection))
                    connection-manager
                    additional-configs)]
        
        (log/info "Connection manager created with" 
                  (count (:connections final-manager)) "connections")
        
        {:connection-manager final-manager
         :primary-client-atom primary-client-atom
         :normalized-config normalized-config}))))

(defn close-servers-with-connection-manager
  "Enhanced version of close-servers that handles connection managers.
   
   This function handles shutdown for both legacy single-connection
   and new multi-connection setups.
   
   Takes either:
   - nrepl-client-atom (legacy format)
   - {:connection-manager <manager> :primary-client-atom <atom>} (new format)"
  [server-state]
  (log/info "Shutting down servers with connection manager support")
  (try
    (cond
      ;; New connection manager format
      (and (map? server-state) (:connection-manager server-state))
      (let [{:keys [connection-manager primary-client-atom]} server-state]
        (log/info "Shutting down connection manager with" 
                  (count (get connection-manager :connections)) "connections")
        
        ;; Close all additional connections first
        (doseq [[port connection] (:connections connection-manager)]
          (when (and (not= port (:default-port connection-manager))
                     (= :connected (:status connection)))
            (log/info "Stopping additional connection on port:" port)
            (try
              (nrepl/stop-polling (:client-map connection))
              (catch Exception e
                (log/warn e "Error stopping additional connection on port:" port)))))
        
        ;; Close primary connection and MCP server
        (when-let [primary-client @primary-client-atom]
          (log/info "Stopping primary nREPL polling")
          (nrepl/stop-polling primary-client)
          (when-let [mcp-server (:mcp-server primary-client)]
            (log/info "Closing MCP server gracefully")
            (.closeGracefully mcp-server)))
        
        (log/info "Connection manager shutdown completed"))
      
      ;; Legacy single-connection format (atom)
      (instance? clojure.lang.Atom server-state)
      (do
        (log/info "Using legacy shutdown process")
        (close-servers server-state))
      
      :else
      (throw (ex-info "Invalid server state format" {:server-state server-state})))
    
    (catch Exception e
      (log/error e "Error during server shutdown")
      (throw e))))

(defn get-connection-for-tool
  "Gets the appropriate connection for a tool.
   
   Supports both legacy single-connection and new multi-connection setups.
   
   Parameters:
   - server-state: Either nrepl-client-atom (legacy) or connection manager state
   - tool-connection-spec: Optional connection specification
     - nil: use default connection
     - integer: use connection on specific port
     - :clojure/:cljs/etc: use connection of specific type
   
   Returns the nrepl-client-map to use for the tool."
  [server-state tool-connection-spec]
  (cond
    ;; Legacy single-connection format
    (instance? clojure.lang.Atom server-state)
    (do
      (when tool-connection-spec
        (log/warn "Tool connection specification ignored in legacy mode:" tool-connection-spec))
      @server-state)
    
    ;; New connection manager format
    (and (map? server-state) (:connection-manager server-state))
    (let [{:keys [connection-manager]} server-state]
      (cond
        ;; No specification - use default
        (nil? tool-connection-spec)
        (conn-mgr/get-default-connection connection-manager)
        
        ;; Port number specification
        (integer? tool-connection-spec)
        (conn-mgr/get-connection-by-port connection-manager tool-connection-spec)
        
        ;; Type specification
        (keyword? tool-connection-spec)
        (conn-mgr/get-connection-by-type connection-manager tool-connection-spec)
        
        :else
        (do
          (log/warn "Invalid tool connection specification:" tool-connection-spec)
          (conn-mgr/get-default-connection connection-manager))))
    
    :else
    (throw (ex-info "Invalid server state format" {:server-state server-state}))))

(defn create-connection-aware-tool
  "Enhanced version of create-async-tool that supports connection specifications.
   
   Takes a map with the following keys:
    :name                - The name of the tool
    :description         - A description of what the tool does
    :schema              - JSON schema for the tool's input parameters
    :tool-fn             - Function that implements the tool's logic.
                           Signature: (fn [exchange args-map clj-result-k] ... )
    :connection-port     - Optional: specific port number to use
    :connection-type     - Optional: connection type (:clojure, :cljs, etc.)
    :server-state        - Server state (either nrepl-client-atom or connection manager)
   
   The tool-fn will receive the appropriate nrepl-client-map based on the connection specification."
  [{:keys [name description schema tool-fn connection-port connection-type server-state] :as tool-spec}]
  
  (let [connection-spec (or connection-port connection-type)
        enhanced-tool-fn (fn [exchange args-map clj-result-k]
                          (try
                            (let [nrepl-client-map (get-connection-for-tool server-state connection-spec)]
                              (if nrepl-client-map
                                ;; Call the original tool function with the appropriate connection
                                (tool-fn exchange args-map clj-result-k nrepl-client-map)
                                ;; Handle case where connection is not available
                                (clj-result-k [(str "Connection not available - "
                                                   (if connection-spec
                                                     (str "specified: " connection-spec)
                                                     "default connection"))] true)))
                            (catch Exception e
                              (log/error e "Error in connection-aware tool:" name)
                              (clj-result-k [(str "Tool error: " (.getMessage e))] true))))]
    
    ;; Create the tool using the existing create-async-tool function
    (create-async-tool (assoc tool-spec
                             :tool-fn enhanced-tool-fn))))

(defn add-connection-aware-tool
  "Helper function to create a connection-aware tool and add it to the server.
   
   Takes an MCP server and a tool specification map with:
    :name                - The name of the tool
    :description         - A description of what the tool does  
    :schema              - JSON schema for the tool's input parameters
    :tool-fn             - Function that implements the tool's logic
    :connection-port     - Optional: specific port number to use
    :connection-type     - Optional: connection type (:clojure, :cljs, etc.)
    :server-state        - Server state (either nrepl-client-atom or connection manager)"
  [mcp-server tool-spec]
  (.removeTool mcp-server (:name tool-spec))
  (-> (.addTool mcp-server (create-connection-aware-tool tool-spec))
      (.subscribe)))

;; Backward compatibility wrapper that works with both legacy and new formats
(defn add-tool-with-connection-support
  "Enhanced version of add-tool that supports both legacy and connection-aware tools.
   
   If tool-map contains :connection-port or :connection-type, creates a connection-aware tool.
   Otherwise, uses the existing legacy behavior."
  [mcp-server tool-map server-state]
  (if (or (:connection-port tool-map) (:connection-type tool-map))
    ;; New connection-aware tool
    (add-connection-aware-tool mcp-server (assoc tool-map :server-state server-state))
    ;; Legacy tool - use existing add-tool
    (add-tool mcp-server tool-map)))


