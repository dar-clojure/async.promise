(ns dar.async.promise
  (:refer-clojure :exclude [deliver])
  (:import (java.util.concurrent.atomic AtomicReference)))

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

(deftype ^:private State [value has-value? callbacks aborted?])

(deftype Promise [^AtomicReference state abort-cb]
  IPromise
  (deliver! [this v] (let [^State s (.get state)
                           callbacks (.-callbacks s)]
                       (when-not (.-has-value? s)
                         (if (.compareAndSet state s (State. v true nil true))
                           (doseq [cb callbacks]
                             (cb v))
                           (recur v)))))

  (delivered? [this] (.-has-value? ^State (.get state)))

  (then [this cb] (let [^State s (.get state)]
                    (if (.-has-value? s)
                      (do
                        (cb (.-value s))
                        nil)
                      (if-not (.compareAndSet state s
                                (State. nil false
                                  (if-let [callbacks (.-callbacks s)]
                                    (conj callbacks cb)
                                    [cb])
                                  (.-aborted? s)))
                        (recur cb)))))

  (value [this] (.-value ^State (.get state)))

  (abort! [this] (when abort-cb
                   (let [^State s (.get state)]
                     (when-not (.-aborted? s)
                       (if (.compareAndSet state s
                             (State. nil false (.-callbacks s) true))
                         (do
                           (abort-cb)
                           nil)
                         (recur)))))))

(defn new-promise
  ([] (new-promise nil))
  ([abort-cb]
   (Promise.
     (AtomicReference. (State. nil false nil false))
     abort-cb)))
