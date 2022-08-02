(ns runway.core
  (:require [axle.core :as watch]
            [beckon :as sig]
            [cinch.core :as util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.namespace.dependency :as deps]
            [clojure.tools.namespace.dir :as dir]
            [clojure.tools.namespace.reload :as reload]
            [clojure.tools.namespace.track :as track]
            [com.stuartsierra.component :as component]
            [com.stuartsierra.dependency :as c-deps]
            [taoensso.timbre :as log])
  (:import [clojure.lang ExceptionInfo IPending Namespace]
           com.stuartsierra.component.SystemMap
           java.io.FileNotFoundException
           java.lang.management.ManagementFactory))

(defonce system-sym
  ;; Symbol for global system constructor fn. Careful, defonce will
  ;; not prevent this var from being thrown out by c.t.n. If this
  ;; namespace gets reloaded, the system will get in an unrecoverable
  ;; state since we forget where to find the root system
  ;; component. This should never be a problem in projects that use
  ;; this library, only when developing on this namespace directly.
  nil)

(defonce system
  ;; Global system var.
  nil)

(defn- apply-components
  [components]
  (reduce-kv
   (fn [acc k [c & args]]
     (assoc acc k (apply c args)))
   {}
   components))

(defn assemble-system
  "Assembles system given components and a dependencies map."
  [components dependencies]
  (as-> (apply-components components) %
    (mapcat identity %)
    (apply component/system-map %)
    (component/system-using % dependencies)))

(defn merge-deps
  "Merges a component dependency graph, preserving vectors."
  [& deps]
  (apply merge-with (comp vec distinct concat) deps))

(defn- init
  "Constructs the current system."
  []
  (->> ((find-var system-sym))
       (constantly)
       (alter-var-root #'system)))

(defprotocol Recover
  (recoverable-system [sys failed-id]
    "Given a system that has failed a lifecycle method, and the id of
    the component that threw the error, returns the largest subsystem
    that can be fully stopped to cleanly recover from the error."))

(defn recoverable-system-map
  "Recover implementation for a SystemMap."
  [sys failed-id]
  (let [failed (-> sys
                   (component/dependency-graph (keys sys))
                   (c-deps/transitive-dependents failed-id)
                   (conj failed-id))]
    (apply dissoc sys ::state failed)))

(extend SystemMap
  Recover
  {:recoverable-system recoverable-system-map})

(defn- recover-system
  [e]
  (let [{f   :function
         id  :system-key
         sys :system} (ex-data e)]
    (try
      (log/error e "Lifecycle failed" f)
      (log/info "Stopping recoverable system")
      (-> (recoverable-system sys id)
          (component/stop)
          (assoc ::state ::stopped))
      (catch ExceptionInfo e
        (log/error e "Unrecoverable system error")
        (throw e)))))

(defn- alter-system
  [f]
  (letfn [(try-recover [sys]
            (try
              (f sys)
              (catch ExceptionInfo e
                (recover-system e))))]
    (alter-var-root #'system try-recover)))

(defn start
  "Starts the current system if not already started."
  []
  (alter-system
   (fn [sys]
     (if (not= ::running (::state sys))
       (do (log/info "Starting" system-sym)
           (-> ((find-var system-sym))
               (component/start)
               (assoc ::state ::running)))
       sys))))

(defn stop
  "Shuts down and destroys the currently running system."
  []
  (alter-system
   (fn [sys]
     (if (= ::running (::state sys))
       (do (log/info "Stopping" system-sym)
           (-> sys
               (component/stop)
               (assoc ::state ::stopped)))
       sys))))

(defn restart
  []
  (stop)
  (start))

(defn- jvm-start-time
  []
  (.getStartTime (ManagementFactory/getRuntimeMXBean)))

(defn- ns-loaded?
  [sym]
  (try
    (the-ns sym)
    (catch Exception e
      false)))

(defn- resolve-error
  [sym {:keys [on-error]}]
  (let [msg "Failed require or resolve"]
    (case on-error
      :throw (throw (ex-info msg {:sym sym}))
      :log   (log/error msg sym)
      nil    nil))
  false)

(defn try-require
  [sym & opts]
  (try
    (do (-> sym
            (name)
            (symbol)
            (require))
        true)
    (catch FileNotFoundException _
      (resolve-error sym opts))))

(defn resolve-sym
  "Prefer to requiring-resolve for backwards compatibility"
  [sym & opts]
  (try
    (let [n  (symbol (name sym))
          ns (symbol (namespace sym))]
      (require ns)
      (var-get (ns-resolve (find-ns ns) n)))
    (catch Exception _
      (resolve-error sym opts))))

(defn- not-loaded-dependents
  [{dc :dependencies
    dt :dependents}]
  (loop [unloaded   #{}
         [c & more] (remove dt (keys dc))]
    (if c
      (if (ns-loaded? c)
        (recur unloaded more)
        (recur (conj unloaded c)
               (concat more (get dc c))))
      unloaded)))

(defn scan-dirs
  [tracker dirs]
  (let [{deps   ::track/deps
         config ::config
         :as    t} (dir/scan-dirs tracker dirs)]
    (if (:lazy-dependents config)
      (let [f (->> deps
                   (not-loaded-dependents)
                   (partial remove))]
        (-> t
            (update ::track/load f)
            (update ::track/unload f)))
      t)))

(defn tracker
  "Returns a new tracker with a full dependency graph, watching for
  changes since JVM start."
  [{dirs :source-dirs
    :as  config}]
  (-> (track/tracker)
      (assoc ::config config)
      (scan-dirs dirs)
      (dissoc ::track/load ::track/unload)
      (assoc ::dir/time (jvm-start-time))))

(defn remove-fx
  [tracker fx]
  (update tracker ::fx (partial remove fx)))

(defmulti do-fx ::current-fx)

(defmethod do-fx ::scan-dirs
  [{{dirs :source-dirs} ::config
    :as                 tracker}]
  (scan-dirs tracker dirs))

(defmethod do-fx ::start
  [tracker]
  (when (and system-sym system)
    (start))
  tracker)

(defmethod do-fx ::stop
  [tracker]
  (when (and system-sym system)
    (stop))
  tracker)

(defn- system-ns?
  [sym]
  (and system-sym
       (= (str sym)
          (namespace system-sym))))

(defn- component-dependent?
  [tracker sym]
  (-> tracker
      (get-in [::track/deps :dependencies sym])
      (contains? 'com.stuartsierra.component)))

(defn- get-restart-fn
  [tracker]
  (let [restart? (-> tracker
                     (::config)
                     (:restart-fn component-dependent?))]
    (fn [sym]
      (or (system-ns? sym)
          (restart? tracker sym)))))

(defn- restart-paths?
  [{events                  ::events
    {:keys [restart-paths]} ::config}]
  (letfn [(event-path [e]
            (-> e
                (:path)
                (io/file)
                (.getCanonicalPath)))

          (restart-re [r-p]
            (->> r-p
                 (io/file)
                 (.getCanonicalPath)
                 (str "^")
                 (re-pattern)))]
    
    (distinct
     (for [r-p   restart-paths
           e-p   (map event-path events)
           :when (re-find (restart-re r-p) e-p)]
       e-p))))

(defmethod do-fx ::restart
  [{::track/keys [load unload]
    :as          tracker}]
  (let [reload  (distinct (concat load unload))
        restart (-> (get-restart-fn tracker)
                    (filter reload)
                    (concat (restart-paths? tracker)))]
    (cond-> tracker
      (empty? restart) (remove-fx #{::start ::stop})
      (empty? reload)  (remove-fx #{::reload-namespaces
                                    ::refresh-repl}))))

(defn- log-removed-ns
  [{::track/keys [unload load]
    :as          tracker}]
  (some->> (seq (remove (set load) unload))
           (log/info "Removed namespaces")))

(defn- log-loaded-ns
  [{::track/keys [load]
    :as          tracker}]
  (some->> (seq load)
           (log/info "Loading namespaces")))

(defmethod do-fx ::reload-namespaces
  [tracker]
  (log-removed-ns tracker)
  (log-loaded-ns tracker)
  (reload/track-reload tracker))

(defn re-alias!
  [^Namespace ns]
  (doseq [[dep-alias dep] (ns-aliases ns)]
    (try
      (let [new-dep (the-ns (ns-name dep))]
        (doto ns
          (.removeAlias dep-alias)
          (.addAlias dep-alias new-dep)))
      (catch Exception e))))

(defn re-refer!
  [^Namespace ns]
  (doseq [[sym var] (ns-refers ns)]
    (let [m              (meta var)
          source-sym     (:name m)
          source-ns      (find-ns (ns-name (:ns m)))
          source-ns-name (ns-name source-ns)]
      (when-not (= 'clojure.core source-ns-name)
        (ns-unmap ns sym)
        (->> source-sym
             (ns-resolve source-ns)
             (.refer ns sym))))))

(defn- refresh-ns!
  [sym]
  (let [ns (the-ns sym)]
    (re-alias! ns)
    (re-refer! ns)))

(defn- resource-path
  [sym]
  (-> (name sym)
      (str/replace #"\." "/")
      (str/replace #"-" "_")))

(defn- backing-resource
  [sym]
  (let [r (resource-path sym)]
    (or (io/resource (str r ".clj"))
        (io/resource (str r ".cljc"))
        (io/resource (str r "__init.class")))))

(defn- refresh-ns?
  [cache ctn-nses sym]
  (not (or (contains? ctn-nses sym)
           (if (contains? cache sym)
             (get cache sym)
             (backing-resource sym)))))

(defn- refresh-repl
  "Refreshes ns aliases and refers for any namespace that is not backed
  by a Clojure resource. These are generally REPL nses and are not
  maintained by c.t.n. The aliases and refers in these nses must be
  kept consistent with the updating namespace graph. We maintain a
  refresh-cache in the tracker to reduce the number of resource
  lookups, and keep things fast."
  [{cache ::refresh-cache
    deps  ::track/deps
    :as   tracker}]
  (let [ctn-nses (deps/nodes deps)]
    (letfn [(f [new-cache sym]
              (let [r (refresh-ns? cache ctn-nses sym)]
                (when r (refresh-ns! sym))
                (assoc new-cache sym r)))]
      (->> (all-ns)
           (map ns-name)
           (reduce f {})
           (assoc tracker ::resource-index)))))

(defmethod do-fx ::refresh-repl
  ;; Because the REPL namespace is not backed by source, c.t.n. cannot
  ;; reload it. This is good, otherwise REPL vars would not persist
  ;; across reloads. However, this also means REPL aliases and refers
  ;; can reference stale namespace objects after reloads, and must be
  ;; refreshed manually.
  [tracker]
  (refresh-repl tracker))

(defmethod do-fx ::check-errors
  [{error    ::reload/error
    error-ns ::reload/error-ns
    :as      tracker}]
  (if error
    (do (log/error error "Compiler exception in" error-ns)
        (remove-fx tracker #{::start}))
    tracker))

(defn- try-fx
  [{id ::current-fx :as tracker}]
  (try
    (do-fx tracker)
    (catch Exception e
      (when-not (contains? #{::start ::stop} id)
        (log/error e "Uncaught watcher error" id))
      (dissoc tracker ::fx))))

(defn reduce-fx
  [fx tracker events]
  (loop [t (assoc tracker ::fx fx)]
    (let [[id & more] (::fx t)]
      (if id
        (recur (try-fx (assoc t
                              ::fx more
                              ::current-fx id
                              ::events events)))
        t))))

(defn- resolve-restart-fn
  [args]
  (util/update-contains args :restart-fn resolve-sym :on-error :throw))

(defn watcher-config
  [args]
  (merge
   {:watch-fx [::scan-dirs
               ::restart
               ::stop
               ::reload-namespaces
               ::refresh-repl
               ::check-errors
               ::start]}
   (resolve-restart-fn args)
   {:source-dirs (util/get-source-dirs)}))

(defn watcher
  "Starts a task that watches your Clojure files, reloads their
  corresponding namespaces when they change, and then if necessary,
  restarts the running application. Has the options:

  :watch-fx         - Accepts a sequence of runway.core/do-fx
                      multimethod dispatch values.
                      See runway.core/watcher-config for the default
                      list. You can extend watcher functionality via
                      the runway.core/do-fx multimethod. Each method
                      accepts a c.t.n. tracker map and returns and
                      updated one. Just take care: like middleware,
                      the order of fx methods is not commutative.
  :lazy-dependents  - Whether to load dependent namespaces eagerly
                      or lazily. See the section on reloading
                      heuristics for more details.
  :restart-fn       - A symbol to a predicate function that when
                      given a c.t.n. tracker and a namespace symbol,
                      returns a boolean whether or not the system
                      requires a restart due to the reloading of that
                      namespace. This allows you to override why and
                      when the running application is restarted.
  :restart-paths    - A list of paths that should trigger an
                      application restart on change. Note that the
                      paths can be either files or directories, with
                      directories also being matched on changed
                      contents. This is useful if your reloaded
                      workflow depends on static resources that are not
                      Clojure code, but may affect the running
                      application."
  [args]
  (let [{fx      :watch-fx
         r-paths :restart-paths
         s-dirs  :source-dirs
         :as     config} (watcher-config args)]
    (log/info "Watching system...")
    (watch/watch!
     {:paths   (concat s-dirs r-paths)
      :context (tracker config)
      :handler (->> (partial reduce-fx fx)
                    (watch/window 10))})
    {:runway/block true}))

(def default-shutdown-signals
  ["SIGINT"
   "SIGTERM"
   "SIGHUP"])

(defn- shutdown-once-fn
  "Idempotent shutown on POSIX signals of type sig."
  [sig]
  (let [nonce (atom false)]
    (fn []
      (when-not (-> nonce
                    (reset-vals! true)
                    (first))
        (stop)
        (sig/reinit! sig)
        (sig/raise! sig)))))

(defn- trim-signal
  [sig]
  (str/replace-first sig #"^SIG" ""))

(defn init-shutdown-handlers
  [signals]
  (doseq [sig signals]
    (let [sig* (trim-signal sig)]
      (reset! (sig/signal-atom sig*)
              [(shutdown-once-fn sig*)]))))

(defn- reloadable-system?
  [sym]
  (let [sys ((find-var sym))]
    (and (satisfies? component/Lifecycle sys)
         (satisfies? Recover sys))))

(defn go
  "Launches the system once at boot. Options:

  :system          - A qualified symbol that refers to a function
                     that when invoked returns a thing that implements
                     both com.stuartsierra.component/Lifecycle, and
                     runway.core/Recover.
  :shutdown-signal - A list of POSIX signal names on which to attempt
                     a clean system shutdown. POSIX signal names are
                     provided in truncated from, where SIG is dropped
                     from the beginning of the string. For example:
                     SIGINT becomes INT."
  [{sym     :system
    signals :shutdown-signals
    :or     {signals default-shutdown-signals}
    :as     args}]
  (when-not (qualified-symbol? sym)
    (throw (ex-info "Not a fully qualified symbol" args)))

  (require (symbol (namespace sym)))
  (alter-var-root #'system-sym (constantly sym))

  (when-not (find-var sym)
    (throw (ex-info "Could not find system var" args)))

  (when-not (reloadable-system? sym)
    (throw (ex-info "Not a reloadable system" {:system sym})))

  (try
    (init-shutdown-handlers signals)
    (start)
    {:runway/block true}
    (catch Exception e nil)))

(defn- sym->ns
  [x]
  (when (symbol? x)
    (or (some-> x namespace symbol) x)))

(defn- exec-fn?
  [k]
  (and (qualified-symbol? k) (resolve k)))

(defn- load!
  [acc k v ns]
  (require ns)
  (cond-> (update acc :loaded-nses conj ns)
    (exec-fn? k) (update :exec-fns assoc k v)))

(defn- assert-exec-arg-key
  [k]
  (when-not (or (symbol? k) (keyword? k))
    (-> ":exec-args environment key %s error: must be symbol or keyword"
        (format k)
        (Exception.)
        (throw))))

(defn- assert-exec-arg-val
  [k v]
  (when-not (string? v)
    (-> ":exec-args environment key %s error: value %s must be a string"
        (format k v)
        (Exception.)
        (throw))))

(defn- parse-and-load-rf
  [acc k v]
  (if-let [ns (sym->ns k)]
    (if v
      (try
        (load! acc k v ns)
        (catch FileNotFoundException e
          (-> "Could not load namespace %s"
              (format  k)
              (Exception.)
              (throw))))
      acc)
    (do (assert-exec-arg-key k)
        (assert-exec-arg-val k v)
        (assoc-in acc [:env k] v))))

(defn- parse-and-load!
  [exec-args]
  (reduce-kv parse-and-load-rf
             {:exec-fns    {}
              :exec-args   exec-args
              :loaded-nses #{}
              :env         {}}
             exec-args))

(defn- log-env!
  [env]
  (if (try-require 'environ.core)
    (log/info "Merged env" env)
    (log/info "No environ.core, skipping")))

(defn- log-loaded!
  [{:keys [exec-fns exec-args loaded-nses env]}]
  (when (:verbose env)
    (let [fns (seq (map first exec-fns))]
      (log-env! env)
      (log/info "Loaded namespaces" (seq loaded-nses))
      (log/info "Exec fns found" fns)
      (log/info "Exec args" (select-keys exec-args fns)))))

(defn- merge-env!
  [{env :env}]
  (when (try-require 'environ.core)
    (-> (find-var 'environ.core/env)
        (alter-var-root merge env))))

(defn- exec-fns!
  [{exec-fns :exec-fns}]
  (doall
   (for [[sym exec-args] exec-fns]
     (if-let [f (var-get (resolve sym))]
       (f exec-args)
       (throw (ex-info "Could not resolve exec-fn" {:exec-fn sym}))))))

(defn boot-time
  []
  (-> (System/currentTimeMillis)
      (- (jvm-start-time))
      (/ 1000)
      (float)))

(defn- response-rf
  [block? resp]
  (if (map? resp)
    (let [{b :runway/block
           r :runway/ready} resp]
      (when (instance? IPending r) @r)
      (or block? b))
    block?))

(defn- maybe-block
  [responses]
  (let [block? (reduce response-rf false responses)
        t      (boot-time)]
    (log/info (format "Boot time: %.2fs" t))
    (when block? @(promise))))

(defn exec
  "Launches multiple concurrent functions via clojure :exec-args. See
  the README.md for usage details."
  [args]
  (let [config (parse-and-load! args)]
    (log-loaded! config)
    (merge-env! config)
    (maybe-block (exec-fns! config))))

(def cli-opts
  [["-s" "--system SYSTEM_SYM" "System symbol"
    :id :system
    :parse-fn symbol]
   [nil "--shutdown-signals SHUTDOWN_SIGNALS" "List of truncated POSIX signals"
    :id :shutdown-signals
    :parse-fn edn/read-string]
   ["-h" "--help"]])

(defn cli-args
  "Parses CLI options for `runway.core/go` when running a dependent
  ptoject as a main program. During development you should prefer
  clojure -X invocation with `runway.core/exec`."
  [args]
  (cli/parse-opts args cli-opts))

(defn -main
  [& args]
  (let [{options :options
         summary :summary
         :as     parsed} (cli-args args)]
    (if (:help options)
      (println summary)
      (do (go options)
          @(promise)))))
