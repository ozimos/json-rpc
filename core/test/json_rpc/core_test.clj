(ns json-rpc.core-test
  (:refer-clojure :exclude [send])
  (:require
   [jsonista.core :as json]
   [clojure.test :refer [deftest is testing]]
   [json-rpc.client :as client]
   [json-rpc.core :refer [*version* close decode encode send open route uuid]]
   [json-rpc.http :as http]
   [json-rpc.unix :as unix]
   [json-rpc.ws :as ws]
   [shrubbery.core :refer [mock received?]])
  (:import
   (clojure.lang ExceptionInfo)))

(deftest decode-test
  (testing "response with result"
    (is (= (decode (json/write-value-as-string {:jsonrpc *version*
                                    :result  "0x0"
                                    :id      1}))
           {:result "0x0"
            :id     1})))
  (testing "response with error"
    (is (= (decode (json/write-value-as-string {:jsonrpc *version*
                                    :error   {:code    -32602
                                              :message "Method not found"}
                                    :id      1}))
           {:error {:code    -32602
                    :message "Method not found"}
            :id    1}))))

(deftest route-test
  (testing "route returns correct record for scheme"
    (is (= http/clj-http (route "http://postman-echo.com/post")))
    (is (= http/clj-http (route "https://postman-echo.com/post")))
    (is (= ws/gniazdo (route "ws://echo.websocket.org")))
    (is (= ws/gniazdo (route "wss://echo.websocket.org")))
    (is (= unix/unix-socket (route "unix:///var/run/geth.ipc"))))
  (testing "route throws exception on unsupported schemes"
    (is (thrown? ExceptionInfo (route "file:///var/run/geth.ipc")))))

(deftest send-test
  (let [id       (uuid)
        response (json/write-value-as-string {:jsonrpc "2.0"
                                  :result  "0x0"
                                  :id      id})
        client   (mock client/Client {:send response
                                      :close nil})]
    (testing "open can open channels for all supported schemes"
      (doseq [url ["http://postman-echo.com/post"
                   "https://postman-echo.com/post"
                   "ws://echo.websocket.org"
                   "wss://echo.websocket.org"
                   "unix:///var/run/geth.ipc"]]
        (let [channel (open url :route-fn (fn [_] client))]
          (is (= [:send-fn :close-fn] (keys channel)))
          (try
            (is (= {:result "0x0"
                    :id     id}
                   (send channel "eth_blockNumber" [] {:id id})))
            (finally (close channel))))))

    (testing "open works with [[with-open]]"
      (with-open [channel (open "ws://echo.websocket.org")]
        (send channel "eth_blockNumber" [])))

    (testing "open throws exception on unsupported schemes"
      (is (thrown? ExceptionInfo (open "file:///var/run/geth.ipc"))))

    (testing "send throws if request ID and response ID don't match"
      (let [channel (open "http://postman-echo.com/post"
                          :route-fn (fn [_] client))]
        (is (thrown? ExceptionInfo
                     (send channel "eth_blockNumber" [] {:id (uuid)})))))))
