(ns json-rpc.core
  (:refer-clojure :exclude [send])
  (:require
   [clojure.tools.logging :as log]
   [json-rpc.client :as client]
   [json-rpc.http :as http]
   [jsonista.core :as json]
   [json-rpc.unix :as unix]
   [nano-id.core :refer [nano-id]]
   [json-rpc.url :as url]
   [json-rpc.ws :as ws]))

(def ^:dynamic *version*
  "The JSON-RPC protocol version."
  "2.0")

(defn encode
  "Encodes JSON-RPC method and params as a valid JSON-RPC request."
  [request]
  (let [encoded (json/write-value-as-string request)]
    (log/debugf "map => %s, json => %s" request encoded)
    encoded))

(defn decode
  "Decodes result or error from JSON-RPC response"
  [json]
  (let [body (json/read-value json)]
    (log/debugf "json => %s, map => %s" json body)
    (select-keys body [:result :error :id])))

(def ^:private ^:const routes
  {:http  http/clj-http
   :https http/clj-http
   :ws    ws/gniazdo
   :wss   ws/gniazdo
   :unix  unix/unix-socket})

(defn route
  [url]
  (let [scheme (url/scheme url)]
    (if-let [client (routes scheme)]
      client
      (throw (ex-info (format "Unsupported scheme: %s. Supported schemes are: %s."
                              (url/scheme url)
                              (keys routes))
                      {:url url})))))

(defrecord Channel [send-fn close-fn]
  java.io.Closeable
  (close [_]
    (close-fn)))

(defn open
  [url & {route-fn :route-fn}]
  (let [route-fn (or route-fn route)
        client   (route-fn url)
        channel  (client/open client url)]
    (log/debugf "url => %s" url)
    (map->Channel {:send-fn  (partial client/send client channel)
                   :close-fn #(client/close client channel)})))

(defn send
  [{send-fn :send-fn} {id :id :as data}]
  (let [id       (if id id (nano-id 10))
        request (encode (if id (assoc  data :jsonrpc *version*) (assoc data :id id :jsonrpc *version*)))
        response (send-fn request)
        decoded  (decode response)]
    (log/debugf "request => %s, response => %s" request response)
    (if (= id (:id decoded))
      decoded
      (throw (ex-info "Response ID is different from request ID!"
                      {:request  request
                       :response response})))))

(defn close
  [{close-fn :close-fn}]
  (close-fn))
