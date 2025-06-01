(ns clojure-mcp.main
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure-mcp.core :as core]
            [clojure-mcp.nrepl :as nrepl]
            [clojure-mcp.prompts :as prompts]
            [clojure-mcp.tools.project.core :as project]
            [clojure-mcp.resources :as resources]
            [clojure-mcp.config :as config]
            ;; tools
            [clojure-mcp.tools.directory-tree.tool :as directory-tree-tool]
            [clojure-mcp.tools.eval.tool :as eval-tool]
            [clojure-mcp.tools.unified-read-file.tool :as unified-read-file-tool]
            [clojure-mcp.tools.grep.tool :as new-grep-tool]
            [clojure-mcp.tools.glob-files.tool :as glob-files-tool]
            [clojure-mcp.tools.think.tool :as think-tool]
            [clojure-mcp.tools.bash.tool :as bash-tool]
            [clojure-mcp.tools.form-edit.combined-edit-tool :as combined-edit-tool]
            [clojure-mcp.tools.form-edit.tool :as new-form-edit-tool]
            [clojure-mcp.tools.file-edit.tool :as file-edit-tool]
            [clojure-mcp.tools.file-write.tool :as file-write-tool]
            [clojure-mcp.tools.dispatch-agent.tool :as dispatch-agent-tool]
            [clojure-mcp.tools.architect.tool :as architect-tool]
            [clojure-mcp.tools.code-critique.tool :as code-critique-tool]
            [clojure-mcp.tools.project.tool :as project-tool]
            [clojure-mcp.tools.connection-manager.tool :as connection-manager-tool]))

;; Define the resources you want available
(defn my-resources [nrepl-client-map working-dir]
  (keep
   identity
   [(resources/create-file-resource
     "custom://project-summary"
     "PROJECT_SUMMARY.md"
     "A Clojure project summary document for the project hosting the REPL, this is intended to provide the LLM with important context to start."
     "text/markdown"
     (.getCanonicalPath (io/file working-dir "PROJECT_SUMMARY.md")))
    (resources/create-file-resource
     "custom://readme"
     "README.md"
     "A README document for the current Clojure project hosting the REPL"
     "text/markdown"
     (.getCanonicalPath (io/file working-dir "README.md")))
    (resources/create-file-resource
     "custom://claude"
     "CLAUDE.md"
     "The Claude instructions document for the current project hosting the REPL"
     "text/markdown"
     (.getCanonicalPath (io/file working-dir "CLAUDE.md")))
    (resources/create-file-resource
     "custom://llm-code-style"
     "LLM_CODE_STYLE.md"
     "Guidelines for writing Clojure code for the current project hosting the REPL"
     "text/markdown"
     (str working-dir "/LLM_CODE_STYLE.md"))
    (let [{:keys [outputs error]} (project/inspect-project nrepl-client-map)]
      (when-not error
        (resources/create-string-resource
         "custom://project-info"
         "Clojure Project Info"
         "Information about the current Clojure project structure, attached REPL environment and dependencies"
         "text/markdown"
         outputs)))]))

(defn my-prompts [working-dir]
  [{:name "clojure_repl_system_prompt"
    :description "Provides instructions and guidelines for Clojure development, including style and best practices."
    :arguments [] ;; No arguments needed for this prompt
    :prompt-fn (prompts/simple-content-prompt-fn
                "System Prompt: Clojure REPL"
                (str
                 (prompts/load-prompt-from-resource "clojure-mcp/prompts/system/clojure_repl_form_edit.md")
                 (prompts/load-prompt-from-resource "clojure-mcp/prompts/system/clojure_form_edit.md")))}
   (prompts/create-project-summary working-dir)])

(defn my-tools [nrepl-client-atom]
  [;; read-only tools
   (directory-tree-tool/directory-tree-tool nrepl-client-atom)
   (unified-read-file-tool/unified-read-file-tool nrepl-client-atom)
   (new-grep-tool/grep-tool nrepl-client-atom)
   (glob-files-tool/glob-files-tool nrepl-client-atom)
   (think-tool/think-tool nrepl-client-atom)

   ;; eval
   (eval-tool/eval-code nrepl-client-atom)
   ;; currently not safe doen't run in repl process, it really should
   (bash-tool/bash-tool nrepl-client-atom)

   ;; editing tools
   (combined-edit-tool/unified-form-edit-tool nrepl-client-atom)
   (new-form-edit-tool/sexp-replace-tool nrepl-client-atom)
   (file-edit-tool/file-edit-tool nrepl-client-atom)
   (file-write-tool/file-write-tool nrepl-client-atom)

   ;; introspection
   (project-tool/inspect-project-tool nrepl-client-atom)
   
   ;; Agents these are read only
   ;; these require api keys to be configured
   (dispatch-agent-tool/dispatch-agent-tool nrepl-client-atom)
   ;; not sure how useful this is
   (architect-tool/architect-tool nrepl-client-atom)

   ;; experimental 
   (code-critique-tool/code-critique-tool nrepl-client-atom)])

(defn my-tools-with-connection-manager 
  "Enhanced version of my-tools that supports connection manager with additional connection-aware tools."
  [server-state]
  [;; Legacy tools (backward compatible - work with either legacy or connection manager)
   (directory-tree-tool/directory-tree-tool 
    (if (map? server-state) (:primary-client-atom server-state) server-state))
   (unified-read-file-tool/unified-read-file-tool 
    (if (map? server-state) (:primary-client-atom server-state) server-state))
   (new-grep-tool/grep-tool 
    (if (map? server-state) (:primary-client-atom server-state) server-state))
   (glob-files-tool/glob-files-tool 
    (if (map? server-state) (:primary-client-atom server-state) server-state))
   (think-tool/think-tool 
    (if (map? server-state) (:primary-client-atom server-state) server-state))

   ;; Standard eval tool (uses default connection)
   (eval-tool/eval-code 
    (if (map? server-state) (:primary-client-atom server-state) server-state))
   
   ;; Multi-connection eval tool (only available with connection manager)
   (when (map? server-state)
     (eval-tool/multi-connection-eval-tool server-state))

   ;; bash tool
   (bash-tool/bash-tool 
    (if (map? server-state) (:primary-client-atom server-state) server-state))

   ;; editing tools
   (combined-edit-tool/unified-form-edit-tool 
    (if (map? server-state) (:primary-client-atom server-state) server-state))
   (new-form-edit-tool/sexp-replace-tool 
    (if (map? server-state) (:primary-client-atom server-state) server-state))
   (file-edit-tool/file-edit-tool 
    (if (map? server-state) (:primary-client-atom server-state) server-state))
   (file-write-tool/file-write-tool 
    (if (map? server-state) (:primary-client-atom server-state) server-state))

   ;; introspection
   (project-tool/inspect-project-tool 
    (if (map? server-state) (:primary-client-atom server-state) server-state))
   
   ;; Agents (read only)
   (dispatch-agent-tool/dispatch-agent-tool 
    (if (map? server-state) (:primary-client-atom server-state) server-state))
   (architect-tool/architect-tool 
    (if (map? server-state) (:primary-client-atom server-state) server-state))
   (code-critique-tool/code-critique-tool 
    (if (map? server-state) (:primary-client-atom server-state) server-state))
   
   ;; Connection management tools (only available with connection manager)
   (when (map? server-state)
     (connection-manager-tool/get-connection-manager-tools server-state))])

;; not sure if this is even needed
(def nrepl-client-atom (atom nil))

;; Enhanced startup function that supports both legacy and multi-connection modes
(defn start-mcp-server 
  "Starts the MCP server with either legacy single-connection or new multi-connection support.
   
   Automatically detects configuration format:
   - Legacy: {:port 7888} -> single connection mode
   - Shadow: {:port 7888 :shadow-port 7889} -> preserves existing Shadow integration  
   - Multi-connection: {:connections {7888 {...} 7889 {...}}} -> full multi-connection mode
   
   Parameters:
   - nrepl-args: Configuration map
   - options: Optional map with:
     - :force-legacy-mode true -> Force legacy single-connection mode even for multi-connection configs
     - :enable-connection-manager true -> Force connection manager mode for legacy configs"
  ([nrepl-args]
   (start-mcp-server nrepl-args {}))
  ([nrepl-args {:keys [force-legacy-mode enable-connection-manager] :as options}]
   (log/info "Starting MCP server with config:" nrepl-args)
   (log/info "Options:" options)
   
   (try
     ;; Determine startup mode based on configuration and options
     (let [config-type (cond
                        force-legacy-mode :legacy
                        enable-connection-manager :connection-manager
                        (config/is-multi-connection-config? nrepl-args) :connection-manager
                        (config/is-shadow-config? nrepl-args) :shadow-preserved
                        :else :legacy)]
       
       (log/info "Detected startup mode:" config-type)
       
       (case config-type
         :legacy
         (start-mcp-server-legacy nrepl-args)
         
         (:connection-manager :shadow-preserved)
         (start-mcp-server-with-connection-manager nrepl-args)))
     
     (catch Exception e
       (log/error e "Failed to start MCP server")
       (throw e)))))

(defn start-mcp-server-legacy
  "Legacy startup mode - preserves existing behavior exactly."
  [nrepl-args]
  (log/info "Starting MCP server in legacy single-connection mode")
  (let [nrepl-client-map (core/create-and-start-nrepl-connection nrepl-args)
        working-dir (config/get-nrepl-user-dir nrepl-client-map)
        resources (my-resources nrepl-client-map working-dir)
        _ (reset! nrepl-client-atom nrepl-client-map)
        tools (my-tools nrepl-client-atom)
        prompts (my-prompts working-dir)
        mcp (core/mcp-server)]
    (doseq [tool tools]
      (core/add-tool mcp tool))
    (doseq [resource resources]
      (core/add-resource mcp resource))
    (doseq [prompt prompts]
      (core/add-prompt mcp prompt))
    (swap! nrepl-client-atom assoc :mcp-server mcp)
    (log/info "MCP server started successfully in legacy mode")
    nil))

(defn start-mcp-server-with-connection-manager
  "Enhanced startup mode with connection manager support."
  [nrepl-args]
  (log/info "Starting MCP server with connection manager")
  (let [{:keys [connection-manager primary-client-atom normalized-config] :as server-state} 
        (core/create-connection-manager-from-config nrepl-args)
        
        primary-client-map @primary-client-atom
        working-dir (config/get-nrepl-user-dir primary-client-map)
        resources (my-resources primary-client-map working-dir)
        
        ;; Use enhanced tools that support connection manager
        tools (flatten (filter identity (my-tools-with-connection-manager server-state)))
        prompts (my-prompts working-dir)
        mcp (core/mcp-server)]
    
    (log/info "Adding" (count tools) "tools to MCP server")
    (doseq [tool tools]
      (if (map? tool)
        ;; New-style tool with connection specifications
        (core/add-tool-with-connection-support mcp tool server-state)
        ;; Legacy-style tool
        (core/add-tool mcp tool)))
    
    (doseq [resource resources]
      (core/add-resource mcp resource))
    
    (doseq [prompt prompts]
      (core/add-prompt mcp prompt))
    
    ;; Store server state for proper cleanup
    (swap! primary-client-atom assoc :mcp-server mcp)
    (reset! nrepl-client-atom server-state) ; For compatibility, though this changes the format
    
    (log/info "MCP server started successfully with connection manager")
    (log/info "Available connections:" (keys (:connections connection-manager)))
    nil))

;; -Djdk.attach.allowAttachSelf is needed on the nrepl server if you want the mcp-server eval tool
;; to be able to interrupt long running evals

;; TODO make a main fn that uses clojure.tools.cli and takes port host and tls-keys-file args
