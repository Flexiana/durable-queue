(ns pod.flexiana.durable-queue
  (:require
   [bencode.core :as bencode]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [durable-queue :as dq])
  (:import
   [java.io EOFException PushbackInputStream])
  (:gen-class))

(set! *warn-on-reflection* true)

(def debug? false)

(defn debug [& args]
  (when debug?
    (binding [*out* (io/writer "/tmp/durable-queue-debug.log" :append true)]
      (apply println args))))

(def stdin (PushbackInputStream. System/in))

(defn pod-write
  [v]
  (bencode/write-bencode System/out v)
  (.flush System/out))

(defn pod-read-string
  [^"[B" v]
  (String. v))

(defn pod-read
  []
  (bencode/read-bencode stdin))

(defn get-queue
  [queue-path]
  (debug (str "get-queue: " queue-path))
  (dq/queues queue-path {}))

(defn stats
  [queue-path]
  (debug (str "stats: " queue-path))
  (dq/stats (get-queue queue-path)))

(defn take!
  [queue-path topic time-out time-out-response]
  (debug (format "take! queue-path: %s, topic: %s, time-out: %s, time-out-response: %s" queue-path topic time-out time-out-response))
  (let [msg (dq/take! (get-queue queue-path)
                      topic
                      time-out
                      time-out-response)]
    (if (= msg time-out-response)
      msg
      (do
        (dq/complete! msg)
        (deref msg)))))

(defn put!
  [queue-path topic data]
  (debug (format "put! queue-path: %s, topic: %s, data: %s" queue-path topic data))
  (dq/put! (get-queue queue-path)
           topic
           data))

(def lookup
  {'pod.flexiana.durable-queue/stats stats
   'pod.flexiana.durable-queue/take! take!
   'pod.flexiana.durable-queue/put! put!})

(defn -main
  [& _args]
  (loop []
    (let [message (try
                    (pod-read)
                    (catch EOFException _ ::EOF))]
      (when-not (identical? ::EOF message)
        (let [op (-> message
                     (get "op")
                     pod-read-string
                     keyword)
              id (some-> (get message "id")
                         pod-read-string)
              id (or id "unknown")]
          (case op
            :describe
            (do
              (pod-write
               {"format" "edn"
                "namespaces"
                [{"name" "pod.flexiana.durable-queue"
                  "vars" [{"name" "stats"}
                          {"name" "take!"}
                          {"name" "put!"}]}]
                "id"     id
                "ops"    {"shutdown" {}}})
              (recur))

            :invoke
            (do
              (try
                (let [fn-name (-> message
                                  (get "var")
                                  pod-read-string
                                  symbol)
                      _       (debug (str "fn-name: " fn-name))
                      args    (-> message
                                  (get "args")
                                  pod-read-string
                                  edn/read-string)
                      _       (debug (str "args: " args))]
                  (if-let [f (get lookup fn-name)]
                    (let [result (apply f args)
                          value  (pr-str result)
                          _      (debug (str "value: " value))
                          reply  {"value"  value
                                  "id"     id
                                  "status" ["done"]}]
                      (pod-write reply))
                    (throw (ex-info (str "Var not found: " fn-name) {}))))
                (catch Throwable e
                  (debug e)
                  (let [reply {"ex-message" (ex-message e)
                               "ex-data"    (pr-str (assoc (ex-data e) :type (class e)))
                               "id"         id
                               "status"     ["done" "error"]}]
                    (pod-write reply))))
              (recur))

            :shutdown
            (System/exit 0)

            ;; default
            (do
              (let [reply {"ex-message" "Unknown op"
                           "ex-data"    (pr-str {:op op})
                           "id"         id
                           "status"     ["done" "error"]}]
                (pod-write reply))
              (recur))))))))
