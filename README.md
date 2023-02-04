<img src="https://i.imgur.com/GH71uSi.png" title="zalky" align="right" width="250"/>

# Runway

[![Clojars Project](https://img.shields.io/clojars/v/io.zalky/runway?labelColor=blue&color=green&style=flat-square&logo=clojure&logoColor=fff)](https://clojars.org/io.zalky/runway)

Coding on the fly, from take-off to landing, with a tool.deps reloadable
build library.

With this library:

1. Power up your `deps.edn` aliases:
   - [Run multiple concurrent functions](#concurrent) via merged deps
     aliases in the same runtime
   - A simple way to [load extra namespaces](#load-ns)
   - Merge in [environment variables](#env-vars) (not enabled by
     default, opt-in via peer dependency)

2. Enjoy [rock-solid live reloading](#reload) of code and lifecycle
   management of your running application:
   - Uses a fork of
     [`clojure.tools.namespace`](https://github.com/zalky/tools.namespace)
     (c.t.n.) that fixes
     [`TNS-6`](https://clojure.atlassian.net/browse/TNS-6), which
     greatly improves c.t.n robustness (a patch has been submitted)
   - The provided implementation is for a `com.stuartsierra.component`
     system. However it can be [extended for any arbitrary build
     framework](#other-framework).
   - Choose how to reload dependent namespaces: eagerly or lazily
   - Uses the new cross-platform [Axle](https://github.com/zalky/axle)
     watcher (performs much better on newer Macs and newer versions of
     Java)
   - More robust error handling, recovery and logging during component
     lifecycle methods
   - Cleanly shutdown your application on interrupt signals

### About Reloaded Workflows

[Reloaded workflows](https://cognitect.com/blog/2013/06/04/clojure-workflow-reloaded)
can be difficult to implement and there are a number of
[known pitfalls](https://github.com/clojure/tools.namespace#warnings-and-potential-problems)
with when using `clojure.tools.namespace`.

However, some of these can be mitigated, and others are not specific
to reloaded workflows and are things that you need to worry about in
any live coding environment. Meanwhile, the benefits that reloaded
workflows bring are significant, especially when live coding alongside
large, running applications. Having automated, enforced heuristics for
how an application behaves as your code changes allows you to a priori
eliminate a whole subset of failure points. And these failure points
are often much trickier than the known gotchas of reloaded code.

So if you're like me, and think reloaded workflows are more than worth
the effort, Runway provides a rock-solid component-based
implementation to do it.

## Contents

1. [Quick Start](#quick-start)
2. [Concurrent Functions](#concurrent)
   - [Writing Concurrent Functions](#concurrent-functions)
3. [Loading a Namespace](#load-ns)
4. [Environment Variables](#env-vars)
5. [Reloaded Workflow](#reload)
   - [Configuration](#reload-config)
   - [Auto REPL Configuration](#repl-auto-setup)
   - [Source Directories](#source)
   - [Reload Heuristics](#reload-heuristics)
   - [Component Lifecycles](#lifecycles)
   - [Error Handling and Recovery](#errors)
   - [Logging](#logging)
6. [System Definition](#system-def)
   - [Example System](#example-system)
   - [System Component Library](#system-components)
7. [Main Invocation](#main)
8. [Other Build Frameworks](#other-framework)
9. [License](#license)   

## Quick Start <a name="quick-start"></a>

Let's say you want to start two concurrent tasks in the same runtime:
an nREPL server and a code watcher. Just put the following in your
`deps.edn` file:

```clj
{:deps    {io.zalky/runway {:mvn/version "0.2.2"}}
 :paths   ["src"]
 :aliases {:repl    {:extra-deps {nrepl/nrepl {:mvn/version "0.8.3"}}
                     :exec-fn    runway.core/exec
                     :exec-args  {runway.nrepl/server {}}}
           :watcher {:exec-fn    runway.core/exec
                     :exec-args  {runway.core/watcher {}}}}}
```

Then you can then run either a single task:

```
clojure -X:repl
```

Or both concurrently:

```
clojure -X:repl:watcher
```

With normal `-X` invocation, only one function is ever run. However
when that function is `runway.core/exec`, it collects and runs any
number of _other_ functions defined in your aliases via merge
semantics. Combined with other Runway features this provides a modular
and flexible approach to what is executed in your runtime.

Note that this minimal example does not start a running application
for you to live code along-side. The next section explains how to do
that.

Also, make sure you have read the [simple guidelines](#reload) on how
to make your code reloading experience more robust. But TL;DR:

1. Do not move your REPL into a namespace backed by a Clojure file on
   the classpath, ex: `(in-ns 'ns.backed.by.my.clojure.file)`

2. Instead require and alias any namespaces you want to use in your
   REPL namespace

3. No AOT compile, no defonce

Additionally, if you use Cider you might want to include some nREPL
middleware in your `:repl` alias dependencies:

```clj
cider/cider-nrepl {:mvn/version "0.28.5"}   ; or whatever your cider version is
refactor-nrepl/refactor-nrepl {:mvn/version "3.5.5"}
```

## Concurrent Functions <a name="concurrent"></a>

Runway provides a means to run multiple concurrent functions via deps
aliases. This is mostly useful when these concurrent functions need
access to the same runtime, otherwise you would just run them as
separate processes. Lets say you want to start a
`com.stuartsierra.component` based application server, an nREPL
server, and a file watcher to reload code. Runway already provides
three built-in functions that do this for you.

Just configure a `deps.edn` that looks like the following:

```clj
{:deps    {io.zalky/runway {:mvn/version "0.2.2"}}
 :paths   ["src"]
 :aliases {:dev    {:extra-deps {nrepl/nrepl {:mvn/version "0.8.3"}}
                    :exec-fn    runway.core/exec
                    :exec-args  {runway.nrepl/server {}
                                 runway.core/watcher {}}}
           :server {:exec-fn   runway.core/exec
                    :exec-args {runway.core/go {:system my.project/app}}}}}
```

The `:exec-args` of each alias define a set of function symbol and
argument pairs. Here, the `:dev` alias defines both an nREPL server
and a code watcher task, and the `:server` alias defines an
application server. If you now run:

```
clojure -X:dev:server
```

Clojure will first merge those aliases according to the normal
semantics of -X invocation, and then pass their combined `:exec-args`
map to `runway.core/exec`. Runway will then locate the functions
declared in the combined `:exec-args` maps, load their namespaces, and
run each of them concurrently with their respective arguments.

Effectively the alias that gets run is:

```clj
{:extra-deps {nrepl/nrepl {:mvn/version "0.8.3"}}
 :exec-fn    runway.core/exec
 :exec-args  {runway.nrepl/server {}
              runway.core/watcher {}
              runway.core/go      {:system my.project/app}}}
```

Easy. Your function aliases are composable in any combination without
having to re-write them, and their `:exec-args` are merged in the
order in which you invoked your aliases.

Any truthy function argument like `{}` or `:arg` will be passed along
to the function, whereas any falsy value indicates that the function
should not be run. Take the alias:

```clj
{:watcher/disable {:exec-fn   runway.core/exec
                   :exec-args {runway.core/watcher false}}}
```

This can be used to disable the watcher in other aliases. The
following will be merged in order:

```
clojure -X:dev:server:watcher/disable
```

There is also the option to merge in `:exec-args` via command line
arguments. The following will merge the `:exec-args` of the two
aliases `:dev` and `:server`, along with the command line edn:

```
clojure -X:dev:server '{my.project/my-process {:my "cli_arg"}}'
```

The effective alias that gets run is:

```clj
{:extra-deps {nrepl/nrepl {:mvn/version "0.8.3"}}
 :exec-fn    runway.core/exec
 :exec-args  {runway.nrepl/server   {}
              runway.core/watcher   {}
              runway.core/go        {:system my.project/app}
              my.project/my-process {:my "cli_arg"}}}
```

To see debugging information about what namespaces, functions,
`:exec-args`, and env variables are being merged in by Runway, simply
add `:verbose "true"` to your `:exec-args`:

```
clojure -X:dev:server '{my.project/my-process {:my "cli_arg"} :verbose "true"}'

22-09-27 16:19:45 zalky INFO [runway.core:488] - No environ.core, skipping
22-09-27 16:19:45 zalky INFO [runway.core:495] - Loaded namespaces (runway.nrepl runway.core my.project)
22-09-27 16:19:46 zalky INFO [runway.core:496] - Exec fns found (runway.nrepl/server runway.core/watcher runway.core/go my.project/my-process)
22-09-27 16:19:46 zalky INFO [runway.core:497] - Exec args {runway.nrepl/server {}, runway.core/watcher {}, runway.core/go {:system my.project/app} my.project/my-process {:my "cli_arg"}}
```

Note the string quotes around `:verbose "true"`. We'll come back to
that later.

### Writing Concurrent Functions <a name="concurrent-functions"></a>

Run functions can be defined anywhere in your code. If your function
is a long-running concurrent process that needs the main thread to
block and stay alive, then it should return a response map:

```clj
{:runway/block true}
```

Once all processes have been launched, Runway will print a boot time:

```
22-09-26 03:43:40 zalky INFO [runway.core:132] - Starting my.project/app
22-09-26 03:43:40 zalky INFO [runway.build:18] - Transitive dependency started
22-09-26 03:43:40 zalky INFO [runway.build:27] - Dependency started
22-09-26 03:43:40 zalky INFO [runway.build:36] - Dependent started
22-09-26 03:43:40 zalky INFO [runway.build:9] - Singleton started
22-09-26 03:43:40 zalky INFO [runway.core:354] - Watching system...
22-09-26 03:43:40 zalky INFO [runway.nrepl:72] - nREPL server started on port 50929 on host localhost - nrepl://localhost:50929
22-09-26 03:43:40 zalky INFO [runway.core:490] - Boot time: 2.70s
```

If you return a `:runway/ready` promise in your response map, the boot
time will not print until your promise has been delivered. For
example, you could define a function like so:

```clj
(ns my.project)

(defn my-process
  [exec-args]
  (let [ready (promise)]
    (future
      (init-process! exec-args)
      (deliver ready true)
      (run-process! exec-args))
    {:runway/block true
     :runway/ready ready}))
```

See the `runway.nrepl/server` function for a real example. You could
then configure your function in an alias:

```clj
{:my-process {:exec-fn    runway.core/exec
              :exec-args  {my.project/my-process {:my "arg"}}}}
```

And invoke it with:

```clj
clojure -X:dev:server:my-process
```

## Loading a Namespace <a name="load-ns"></a>

Qualified symbols are interpreted as a run functions by
`runway.core/exec`. _Unqualified_ symbols are interpreted as
namespaces to load. Given:

```clj
{:deps    {io.zalky/runway {:mvn/version "0.2.2"}}
 :paths   ["src"]
 :aliases {:server {:exec-fn   runway.core/exec
                    :exec-args {runway.core/go   {:system my.project/app}
                                my.project.other true}}}}
```
Then

```
clojure -X:server
```

Will run the `runway.core/go` function as well as load the
`my.project.other` namespace, presumably for side-effects.

As always, to see debugging information about what namespaces are
being loaded by Runway, use `:verbose "true"`:

```
clojure -X:server '{:verbose "true"}'
```

## Environment Variables <a name="env-vars"></a>

[`Environ`](https://github.com/weavejester/environ) is a library that
can import environment settings from a number of different sources,
including environment variables.

One thing to be aware of when using Environ is that it loads _all_
your environment variables, including potentially sensitive ones, into
memory and stores them in `environ.core/env`. For this reason, Runway
treats Environ as a peer dependency.

If you do not include Environ as a dependency in your `deps.edn` (and
it is not available on the classpath), then no variables are loaded
and all the environment features in this section will be ignored.

If you do include it, Runway provides you with a way to merge
additional env variables into `environ.core/env` using the
`:exec-args` maps in your `deps.edn` aliases:

```clj
{:deps    {io.zalky/runway {:mvn/version "0.2.2"}
           environ/environ {:mvn/version "1.2.0"}}
 :paths   ["src"]
 :aliases {:dev         {:extra-deps {nrepl/nrepl {:mvn/version "0.8.3"}}
                         :exec-fn    runway.core/exec
                         :exec-args  {runway.nrepl/server {}
                                      runway.core/watcher {}}}
           :server      {:exec-fn   runway.core/exec
                         :exec-args {runway.core/go {:system my.project/app}
                                     :my-env-var-1  "val1"}}
           :server/opts {:exec-args {:my-env-var-2 "val2"}}}}
```

Any keywords in your `:exec-args` maps are interpreted as env
variables that should be merged into your `environ.core/env` map. If
you now invoke your programs with:

```
clojure -X:repl:server:server/opts
```

You should have access to both `:my-env-var-1` and `:my-env-var-2` in
`environ.core/env`:

```clj
(require '[environ.core :as env])

(select-keys env/env [:my-env-var-1 :my-env-var-2])
=>
{:my-env-var-1 "val1", :my-env-var-2 "val2"}
```

Your alias variables override any values that may have been exported
directly in your environment. For example, let's say we remove the
`:server/opts` alias and invoke just:

```
clojure -X:repl:server
```

Then the value of `:my-env-var-1` will always be `"val1"` (as defined
in `:server`) no matter what is `export`ed by your environment, but
the value of `:my-env-var-2` would depend whether or not you had
`export`ed it as an env VARIABLE:

```
export MY_ENV_VAR_2=env_val
```

The above mechanism provides you with an easy way to configure your
application across development and production environments.

To see debugging information about what env variables are being merged
in by your edn, use `:verbose "true"` (this will _not_ print the full
set of env VARIABLES loaded by from your environment, just those in
edn):

```
clojure -X:repl:server:server/opts '{:verbose "true"}'

22-09-27 17:15:30 zalky INFO [runway.core:494] - Merged env {:my-env-var-1 "val1", :my-env-var-2 "val2", :verbose "true"}
22-09-27 17:15:31 zalky INFO [runway.core:495] - Loaded namespaces (runway.nrepl runway.core)
22-09-27 17:15:31 zalky INFO [runway.core:496] - Exec fns found (runway.nrepl/server runway.core/watcher runway.core/go)
22-09-27 17:15:31 zalky INFO [runway.core:497] - Exec args {runway.nrepl/server {}, runway.core/watcher {}, runway.core/go {:system my.project/app}}
22-09-27 17:15:31 zalky INFO [runway.core:377] - Watching system...
22-09-27 17:15:31 zalky INFO [runway.core:137] - Starting my.project/app
```

Of course, you can merge in variables via your cli `:exec-args`:

```
clojure -X:repl:server:server/opts '{:verbose "true" :my-cli-var "other"}'
22-09-27 17:24:35 zalky INFO [runway.core:494] - Merged env {:my-env-var-1 "val1", :my-env-var-2 "val2", :verbose "true", :my-cli-var "other"}
```

### Env Variable Values Must Be Strings

`:exec-args` are necessarily parsed as edn, and therefore so are the
values in your `:exec-args` maps. However, env variables that you
export in your environment are not:

```
export MY_CLI_VAR=false
```

The above will be loaded as the string `"false"`, which is actually a
truthy value in your running application. Therefore to preserve the
semantics of environment variables, Runway will throw an error if you
try to pass a non-string value to an `:exec-args` env var:

```
clojure -X:repl:server:server/opts '{:my-cli-var false}'
Exception in thread "main" java.lang.Exception: :exec-args environment key :my-cli-var error: value false must be a string
```

This is why we have been passing `:verbose "true"` throughout these
examples, and not `:verbose true`.

## Reloaded Workflow <a name="reload"></a>

Reloaded workflows with Runway can be extremely robust. Three simple
guidelines are all you need to help mitigate pitfalls:

1. Do not move your REPL into a namespace backed by a Clojure file on
   the classpath. Namespaces backed by files are constantly being
   replaced by c.t.n., and if you move your REPL into one like so:

   ```clj
   (in-ns 'ns.backed.by.my.clojure.file)
   ```

   You will find it hard to persist your REPL vars across
   reloads. Whereas, by staying in a namespace that is not backed by a
   file, and therefore not reloaded, your REPL vars will be preserved.

   While you work, Runway will keep your aliases and refers your REPL
   namespaces consistent with the changing namespace graph.

2. Instead require and alias any namespace you want to use in your
   REPL. If you find yourself regularly requiring a common set of
   namespaces see here for [how to automate this](#repl-auto-setup).

3. No AOT compile, no defonce

   While you can use defonce to define vars, because the entire
   namespaces gets replaced by c.t.n., it can't protect those vars
   from being redefined.

Next, some caveats about live-coding that are not specific to reloaded
workflows, but are nevertheless critical to be aware of:

1. Be careful what you `def` in your REPL namespace. _Especially
   objects that implement protocols or interfaces, like system
   components!_ Anything you `def` into your REPL becomes a snapshot
   of your code at a point in time, and can easily get out of sync as
   your other namespaces change. And while Runway updates your REPL
   aliases and refers, it cannot account for stale REPL state that you
   may have captured through `def`s or closures.

   If you are not sure whether the thing you are `def`ing into your
   REPL can become stale, then consider using a `defn` instead.

2. `defmethod`s will be updated, but cannot be outright removed. If
   you really want a stale `defmethod` gone, the fool-proof approach
   is to trigger a reload on the file that contains the `defmulti`
   MultiFn. `clojure.core/remove-method` can also work, but there are
   some edge cases if you also use `clojure.core/prefer-method`.

3. Runway's code reloading is robust enough that you should be able to
   switch git branches that are any distance apart, as long as the
   classpath doesn't change. However, any changes with implications on
   the classpath are likely to cause problems.

### Configuration <a name="reload-config"></a>

Typically you would start at least a development server, a code
watcher, and an nREPL server. Something like this in your `deps.edn`
aliases would work:

```clj
{:dev    {:extra-deps {nrepl/nrepl       {:mvn/version "0.8.3"}
                       cider/cider-nrepl {:mvn/version "0.28.5"}             ; optional
                       refactor-nrepl/refactor-nrepl {:mvn/version "3.5.5"}} ; optional
          :exec-fn    runway.core/exec
          :exec-args  {runway.nrepl/server {}
                       runway.core/watcher {}}}
 :server {:exec-fn   runway.core/exec
          :exec-args {runway.core/go {:system my.project/app}}}}
```

You could start any combination of the above aliases from the command
line:

```
clojure -X:dev:server
```

#### `runway.core/watcher`

Watches your Clojure files, reloads their corresponding namespaces
when they change, and then if necessary, restarts the running
application. It is configurable with the following options:

1. **`:watch-fx`**: accepts a sequence of `runway.core/do-fx`
   multimethod dispatch values. See `runway.core/watcher-config` for
   the default list. You can extend watcher functionality via the
   `runway.core/do-fx` multimethod. Each method behaves like an
   interceptor: it accepts a c.t.n. tracker map and returns and
   updated one, potentially modifying the remaining fx chain. Just
   take care: the order of fx methods is not necessarily commutative.

2. **`:lazy-dependents`**: whether to load dependent namespaces
   eagerly or lazily. See [the section on reloading
   heuristics](#reload_heuristics) for more details.

3. **`:restart-fn`**: A symbol to a predicate function that when given
   a c.t.n. tracker and a namespace symbol, returns a boolean whether
   or not the system requires a restart due to the reloading of that
   namespace. This allows you to override the default logic of when
   the running application is restarted. The defualt is to restart the
   application whenever a direct dependent of
   `com.stuartsierra.component` is reloaded.

4. **`:restart-paths`**: A list of paths that the watcher should
   monitor for changes to trigger an application restart. Note that
   the paths can be either files or entire directories. This is useful
   if your reloaded workflow depends on static resources that are not
   Clojure code, but may affect the running application. For example,
   let's say your application is configured via edn in a `config/edn`
   directory:

   ```
   {:exec-fn   runway.core/exec
    :exec-args {runway.core/watcher {:restart-paths ["config/edn"]}}}
   ```

#### `runway.core/go`

Starts the application once on boot (after boot, you can start or stop
the application manually or via the watcher). It has the following
options:

1. **`:system`**: A symbol that refers to a function that when called
   with no arguments, returns a `com.stuartsierra.component/SystemMap`
   ([see here on how to extend for other build
   frameworks](#other-framework)). In the example above it would be a
   function called `my.project/app`.

1. **`:shutdown-signals`**: A sequence of strings representing POSIX
   interrupt signals (default is `["SIGINT" "SIGTERM" "SIGHUP"]`). On
   receiving such a signal Runway will first attempt to shutdown the
   application before re-raising.

#### `runway.nrepl/server`

Launches an nREPL server for you to connect to, and has the following
options:

1. **`:port`**: nREPL port where to listen to for connections. For
   example you could configure a specific port directly from the
   command line like so:

   ```clj
   clojure -X:dev:server '{runway.nrepl/server {:port 50000}}'
   ```

2. **`:middleware`**: A list of nREPL middleware symbols. Each symbol
   can either directly reference a middleware function, or point to a
   list of more symbols. For example:

   ```clj
   {runway.nrepl/server {:middleware [my.project.nrepl/my-middleware-list]}}
   ```

   `cider.nrepl` and `refactor-nrepl.middleware` is special: if no
   `:middleware` option is provided, but either is on the classpath,
   then their default middleware sets are loaded automatically. So all
   you have to do is include `cider/cider-nrepl` or
   `refactor-nrepl/refactor-nrepl` in your deps, and they should
   work. See `runway.nrepl/default-middleware` for the full list of
   default middleware.

### Auto REPL Configuration <a name="repl-auto-setup"></a>

If you find yourself regularly requiring and aliasing a common set of
namespaces, or performing any other kind of workflow to set up your
REPL environment, simply set up a namespace like so:

```clj
(ns dev.repl) ; The namespace for this file, NOT your REPL namespace

(ns user)     ; Here is your REPL namespace

(require '[my.project.admin :as admin]
         '[my.project.auth :as auth]
         '[my.project.session :as session]
         '[my.project.comms :as comms]
         '[my.project.s3 :as s3]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.set :as set]
         '[datomic.api :as d]
         '[cinch.core :as util]
         '[runway.core :as run]
         '[taoensso.timbre :as log]
         '[taoensso.nippy :as nippy])

(defn my-repl-workflow-helper
  [arg]
  ...)
```

The key thing here is that the location of this file on the classpath,
for example `<classpath>/dev/repl.clj`, does not map to your REPL
namespace. So if your REPL namespace is `user`, you do not want this
file to be `<classpath>/user.clj`. You want to make sure that your
REPL namespace `user` is not backed by a file, so that it plays well
with c.t.n.

Then ensure this namespace is loaded by the appropriate `deps.edn`
alias:

```clj
{:dev {:extra-paths ["path/to/"]         ; <- path to dev/repl.clj
       :extra-deps  {nrepl/nrepl                   {:mvn/version "0.8.3"}
                     cider/cider-nrepl             {:mvn/version "0.28.5"} ; optional
                     refactor-nrepl/refactor-nrepl {:mvn/version "3.5.5"}} ; optional
       :exec-fn     runway.core/exec
       :exec-args   {runway.nrepl/server {}         ; concurrent run fn
                     runway.core/watcher {}         ; concurrent run fn
                     dev.repl            true}}}    ; <- ns loaded here
```

When working with a team, you'll usually want to put your REPL config
in your personal `~/.clojure/deps.edn`, because REPL workflows are
fairly user specific. But given the flexibility Runway gives you to
merge and run aliases, you have an endless number of ways to do so:

```clj
{:repl/config        {:extra-paths ["path/to/"]
                      :exec-fn     runway.core/exec
                      :exec-args   {dev.repl true}}
 :repl/datomic       {:exec-fn   runway.core/exec
                      :exec-args {dev.repl.db true}}
 :repl/elasticsearch {:exec-fn   runway.core/exec
                      :exec-args {dev.repl.elasticsearch true}}
 ...}
```

Note that if you update the above `dev.repl` config namespace, the
watcher will see this, reload it, and automatically reconfigure your
REPL. But remember, this approach is _only_ for setting up your REPL
environment. The evaluation of `dev.repl` should not produce stateful
side-effects in your actual application. This is what
`com.stuartsierra.component` is for.

### Source Directories <a name="source"></a>

Any directories on your classpath will be searched for Clojure files
by the Runway watcher. Use the `:paths` in your `deps.edn` aliases to
determine what is watched.

### Reload Heuristics <a name="reload-heuristics"></a>

First, to trigger a code reload you actually have to change the
contents of a file. Simply saving a file without modifying its
contents will do nothing.

While live coding you can usually rely on runway to at minimum reload
your changed namespaces in dependency order, and additionally reload
the transitive dependents of those changed namespaces. However, the
behaviour of transitive dependents loading can be configured to be
either eager (the default), or lazy.

To understand what this means, we first have to understand the
difference between the namespace dependency graph that
`clojure.tools.namespace` computes from the contents of your source
files, and the actual dependency graph between your namespace objects
in memory.

When you first load your application with the `runway.core/go` task,
only the subset of the code that is required to construct your
application is loaded into memory. Likewise, if you start just a REPL,
only those namespaces that you `require` in your REPL will be
loaded. But once you start a `runway.core/watcher` task, and by
extension a `clojure.tools.namespace` tracker, they will automatically
reload _all_ dependents of a changed namespace that are found
_anywhere in your source directories_, including dependents that have
until that point not been required either by the running application
or your REPL.

For example, let's say you've defined your running application in
namespace `b`, which has a single dependency `a`.

```
  a
 /
b     ; b defines your running app and requires a
```

There's another source file, `c`, that is not required for your
running application, but also requires `a`.

```
  a
 / \
b   c
```

When you start your app with `runway.core/go`, and a
`runway.core/watcher` task, initially only `a` and `b` are loaded into
memory.

However as soon as you modify `a` on disk, both dependents `b` and `c`
are eagerly reloaded by the watcher, even though `c` was not initially
required by your running application. The heuristics are the same if
you start a REPL and a watcher, evaluate `(require 'b)`, and then
modify `a` on disk: `c` will be eagerly reloaded.

The idea is that for any change to source, you want to realize _all_
dependent effects right away. You do not want to wait for changes to
accumulate, only to later find out that conflicts or errors have been
introduced and have become more difficult to resolve. So the eager
realization of dependent source changes is almost always desirable.

However, it may be that loading namespace `c` produces some
side-effects that you'd rather not have happen (side-effects in your
namespaces are best avoided, but such is life). If you really need,
you can configure the Runway watcher to load dependents lazily via the
`:lazy-dependents` option.

With `:lazy-dependents true`, the watcher would not automatically
reload `c` unless it has already been loaded once by some other means,
either as part of the application boot, or explicitly from the REPL
with `(require 'c)`. From that point on, any changes to `a`, would
automatically cause `c` to reload.

### Component Lifecycles <a name="lifecycles"></a>

Just before reloading code, Runway also checks whether any of the
namespaces it is about to update depends directly on
`com.stuartsierra.component`. These namespaces usually define system
components that implement the `com.stuartsierra.component/Lifecycle`
protocol and participate in the running application. Before reloading
any such namespaces, Runway will first stop the running
application. Then if all namespaces are successfully reloaded, Runway
will start the running application again. This process ensures that
all the stateful objects that were loaded as part of your running
application are consistent with your updated code.

At any point, you can manually stop, start or restart your running
application from the REPL with:

```clj
(require '[runway.core :as run])

(run/stop)
(run/start)
(run/restart)
```

If you need access to your running application during live coding, it
resides in the `runway.core/system` var. But remember, never access
this var or its contents outside of your REPL work. For example, you
never want to require or access this var in your application
namespaces: doing so by-passes the component dependency graph and is
considered a component anti-pattern.

### Error Handling and Recovery  <a name="errors"></a>

Runway handles errors in each phase of a reload in different ways:

1. **Component failed `start` lifecycle**: Runway will abort the
   start, and then attempt to recover by stopping the components of
   the system it had already started up to that point.

2. **Component failed `stop` lifecycle**: Runway will abort stopping
   the problem component, but attempt to recover by stopping all other
   components that are _not_ transitive dependents of the problem
   component (transitive dependents should already have been stopped).

3. **Namespace reloading failed**: Runway will not restart the running
   application until all namespace compile errors have been resolved.

### Logging <a name="logging"></a>

Runway implements logging via the excellent
[`com.taoensso/timbre`](https://github.com/ptaoussanis/timbre) library
for maximum extensibility.

Specifically, Lifecycle exceptions are logged out with full component
and system data. However, because components in complex systems can be
quite large, you may want to truncate such output to avoid spamming
terminals and log sinks. You can easily do so using custom timbre
appenders.

## System Definition <a name="system-def"></a>

You can make your `com.stuartsierra.component/SystemMap` any way you
like, but Runway also provides some facilities that make it a bit
easier:

```clj
(ns my.project
  (:require [runway.core :as run]))

(def base-components
  {:dependency [->Dependency arg1 arg2]
   :dependent  [->Dependent]})

(def base-dependencies
  {:dependent [:dependency]})

(defn base-system
  "Symbol that gets passed to runway.core/go"
  []
  (run/assemble-system base-components base-dependencies))
```

Here `->Dependency` is any constructor function that when applied to
its arguments `arg1 arg2`, returns a component that implements the
`com.stuartsierra.component/Lifecycle` protocol.

Defined as such, you can easily re-combine systems according to merge
semantics in arbitrary ways:

```clj
(def fullstack-components
  (->> {:new-dependency [->NewDependency]}
       (merge base-components)))

;; => {:dependency     [->Dependency arg1 arg2]
;;     :dependent      [->Dependent]
;;     :new-dependency [->NewDependency]}

(def fullstack-dependencies
  (->> {:dependent [:new-dependency]}
       (run/merge-deps base-dependencies)))

;; => {:dependent [:dependency :new-dependency]}

(defn fullstack-system
  []
  (run/assemble-system fullstack-components fullstack-dependencies))
```

While a simple `merge` will work on the components maps, note the use
of the `run/merge-deps` on the dependency maps.

### Example System <a name="example-system"></a>

There are a set of stub components assembled into an example system in
the
[`runway.build`](https://github.com/zalky/runway/blob/main/build/runway/build.clj)
namespace. You can run this example system using:

```sh
clojure -X:server:dev
```

You can try updating the components to see how the code and the
running system are reloaded.

### System Component Library <a name="system-components"></a>

You can find a number of useful, ready made components (ex:
websockets, servers, loggers, etc...) in the excellent
[System](https://github.com/danielsz/system) library.

## Main Invocation <a name="main"></a>

You may want to configure your project with a `-main` function. Runway
provides CLI arg parsing for the `runway.core/go` method via
`runway.core/cli-args` (implemented using `clojure.tools.cli`). For
example you could write something like:

```clj
(ns my.project
  (:require [runway.core :as run]))

(defn -main
  [& args]
  (let [{options :options
         summary :summary
         :as     parsed} (run/cli-args args)]
    (if (:help options)
      (println summary)
      (do (run/go options)
          @(promise)))))
```

Here, `args` are CLI args meant for `runway.core/go` (at minimum
`--system my.project/app`), and not args to your running
application. To configure your actual application, prefer environment
variables, edn, or a configuration framework like Zookeeper.

## Other Build Frameworks <a name="other-framework"></a>

The default build implementation provided by Runway is for
`com.stuartsierra.component/SystemMap`. However other build frameworks
can be wrapped to work with Runway. Simply wrap your system in a
record that implements `com.stuartsierra.component/Lifecycle`, and
`runway.core/IRecover`. You probably also want to set a custom
`:restart-fn` watcher predicate. The default `:restart-fn` predicate
restarts your app whenever a direct dependent of
`com.stuartsierra.component` changes, which is probably not what you
want for a non-Component build framework.

Something like this should work:

```clj
(ns my.project
  (:require [com.stuartsierra.component :as component]
            [runway.core :as run]))

(defrecord ComponentWrapper [impl-system]
  component/Lifecycle
  (start [_]
    (try
      (->ComponentWrapper (impl-start-fn impl-system))
      (catch Throwable e
        (let [id  (get-failed-id e)
              sys (get-failed-system e)]
          ;; Re-throw as component error
          (throw
           (ex-info (get-error-msg e)
                    {:system-key id
                     :system     (->ComponentWrapper sys)
                     :function   #'impl-start-fn}
                    e))))))

  (stop [_]
    (try
      (->ComponentWrapper (impl-stop-fn impl-system))
      (catch Throwable e
        (let [id  (get-failed-id e)
              sys (get-failed-system e)]
          ;; Re-throw as component error
          (throw
           (ex-info (get-error-msg e)
                    {:system-key id
                     :system     (->ComponentWrapper sys)
                     :function   #'impl-stop-fn}
                    e))))))

  run/Recover
  (recoverable-system [_ failed-id]
    (-> impl-system
        (impl-recoverable-subsystem)
        (->ComponentWrapper))))

(defn wrapped-app
  "Passed to runway.core/go"
  []
  (->ComponentWrapper (impl-system-constructor)))
```

There are five important things to note:

1. On error you need to re-throw a `com.stuartsierra.component`
   compatible error. A component error is of type
   `clojure.lang.ExceptionInfo` and contains at minimum:

   - `:system-key`: This is the failed component id
   - `:system`: This is the failed implementation system, wrapped in a
   `ComponentWrapper`
   - `:function`: This is the lifecycle that failed, only used for logging

   Both the `:system-key` and the wrapped `:system` are passed to your
   `recoverable-system` implementation, which needs to return a
   wrapped subsystem that Runway will attempt to stop to recover from
   the error. This subsystem should include everything that still
   needs to be stopped _excluding_ the failed component.

   If your `impl-start-fn` and `impl-stop-fn` do not throw errors, but
   instead return errors as data, simply parse the data and then throw
   a component exception.

2. On success there's not much to do. Just return the started or
   stopped implementation system, making sure it is wrapped.

3. Wherever we delegate back to Runway, our implementation system must
   always be wrapped in a `ComponentWrapper`. This includes the return
   values of each protocol method, as well as in the re-thrown
   component exception. It also includes the recoverable subsystem and
   the constructor function passed to `runway.core/go`.

4. If there's no need to recover your system on lifecycle errors, or
   maybe `impl-start-fn` and `impl-stop-fn` handle recovery
   directly, you can always choose not to re-throw errors in the
   `start` and `stop` `Lifecycle` methods. In this case
   `recoverable-system` is never called and `ComponentWrapper` becomes
   trivial.

5. Make sure you do not accidentally double wrap the component.

See
[`runway.wrapped`](https://github.com/zalky/runway/blob/main/build/runway/wrapped.clj)
for a working stub that implements this full pattern where the other
"framework" is just a simple Clojure map. You can run this wrapped
example system from the command line using:

```clj
clojure -X:server:dev '{runway.core/go {:system runway.wrapped/wrapped-app}}'
```

## License <a name="license"></a>

Runway is distributed under the terms of the Apache License 2.0.
