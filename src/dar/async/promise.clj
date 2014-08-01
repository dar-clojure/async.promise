(ns dar.async.promise
  (:refer-clojure :exclude [deliver])
  (:import (java.util.concurrent.atomic AtomicBoolean)))

(set! *warn-on-reflection* true)

(defprotocol IPromise
  (abort! [this]
    "Notifies the underlying async computation that the
    result is no longer needed. Generally it should
    release all resources and yield an exception as a promised value.
    However, this method is advisory. Computation might still
    complete successfully after abort. This method might be called
    several times.")
  (deliver! [this val]
    "Delivers the supplied value to promise and notifies
    all registered `then` callbacks. Subequent calls
    will be ignored.")
  (delivered? [this]
    "Returns true if the result was already delivered.")
  (then [this cb]
    "Registers a result handler function. It might be called
    immedeately.")
  (value [this]
    "Returns the delivered value or nil"))

(extend-protocol IPromise
  nil
  (abort! [_])
  (deliver! [_ _] nil)
  (delivered? [_] true)
  (then [_ cb] (cb nil) nil)
  (value [_] nil)

  Object
  (abort! [_])
  (deliver! [_ _] nil)
  (delivered? [_] true)
  (then [this cb] (cb this) nil)
  (value [this] this))

(deftype ^:private State [value has-value? callbacks])

(defn- deliver [^State s v]
  (if (.-has-value? s)
    (State. (.-value s) true nil)
    (State. v true (.-callbacks s))))

(defn- add-callback [^State s cb]
  (if (.-has-value? s)
    s
    (State. nil false (if-let [callbacks (.-callbacks s)]
                        (conj callbacks cb)
                        [cb]))))

(defn- clear-callbacks [^State s]
  (State. (.-value s) (.-has-value? s) nil))

(deftype Promise [state abort-cb ^AtomicBoolean aborted?]
  IPromise
  (deliver! [this v] (let [^State s (swap! state deliver v)]
                       (when-let [callbacks (.-callbacks s)]
                         (swap! state clear-callbacks)
                         (doseq [cb callbacks]
                           (cb v)))))

  (delivered? [this] (.-has-value? ^State @state))

  (then [this cb] (let [^State s (swap! state add-callback cb)]
                    (when (.-has-value? s)
                      (cb (.-value s))
                      nil)))

  (value [this] (.-value ^State @state))

  (abort! [this] (when abort-cb
                   (when (.compareAndSet aborted? false true)
                     (abort-cb this)
                     nil))))

(defn new-promise
  ([] (new-promise nil))
  ([abort-cb]
   (Promise.
     (atom (State. nil false nil))
     abort-cb
     (if abort-cb
       (AtomicBoolean. false)))))
