(ns runway.nrepl
  (:require [clojure.string :as str]
            [nrepl.cmdline :as cmd]
            [runway.core :as run]
            [taoensso.timbre :as log]))

(def default-middleware
  '[cider.nrepl.middleware/cider-middleware
    refactor-nrepl.middleware/wrap-refactor])

(defn- var->symbol
  "Backwards compatible with older clj, otherwise use
  clojure.core/symbol."
  [var]
  (-> (str var)
      (str/replace-first #"^#'" "")
      (symbol)))

(defn- build-middleware
  [mw]
  (loop [res          []
         [sym & more] (or mw `[default-middleware])]
    (if sym
      (if-let [x (run/resolve-sym sym :on-error (when mw :log))]
        (if (sequential? x)
          (recur res (concat x more))
          (recur (conj res sym) more))
        (recur res more))
      res)))

(defn- server-config
  [args]
  (-> args
      (update :middleware build-middleware)
      (cmd/server-opts)))

(defn server
  "Launches an nREPL server with the following options:

  :port       - nREPL port on which to listen to for connections.
  :middleware - A list of nREPL middleware symbols. Each symbol
                can either directly reference a middleware fn, or
                a list of more symbols. If no value is provided,
                and either cider.nrepl or refactor-nrepl.middleware
                are on the classpath, then their default middleware
                are loaded automatically. See
                runway.nrepl/default-middleware for the full list."
  [args]
  (let [ready (promise)]
    (future
      (let [config (server-config args)
            server (cmd/start-server config)]
        (cmd/ack-server server config)
        (log/info (cmd/server-started-message server config))
        (cmd/save-port-file server config)
        (deliver ready true)
        (when (:interactive config)
          (cmd/interactive-repl server config))
        @(promise)))
    {:runway/block true
     :runway/ready ready}))
