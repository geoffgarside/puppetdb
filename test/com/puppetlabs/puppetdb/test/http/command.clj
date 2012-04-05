(ns com.puppetlabs.puppetdb.test.http.command
  (:require [com.puppetlabs.utils :as pl-utils]
            [cheshire.core :as json]
            [clj-time.format :as time])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.testutils]
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [com.puppetlabs.mq]))

(use-fixtures :each with-test-db with-test-mq with-http-app)

(defn make-request
  [post-body]
  (let [request (request :post "/commands")]
    (-> request
        (assoc-in [:headers "accept"] "application/json")
        (body post-body))))

(deftest command-endpoint
  (testing "Commands submitted via REST"

    (testing "should work when well-formed"
      (let [payload  "This is a test"
            checksum (pl-utils/utf8-string->sha1 payload)
            req      (make-request {:payload "This is a test" :checksum checksum})
            resp     (*app* req)]
        (is (= (:status resp) 200))
        (is (= (get-in resp [:headers "Content-Type"]) "application/json"))
        (is (= (json/parse-string (:body resp)) true))))

    (testing "should return 400 when missing params"
      (let [req  (make-request {})
            resp (*app* req)]
        (is (= (:status resp) 400))))

    (testing "should return 400 when checksums don't match"
      (let [req  (make-request {:payload "Testing" :checksum "something bad"})
            resp (*app* req)]
        (is (= (:status resp) 400))))))

(deftest receipt-timestamping
  (let [good-payload       (json/generate-string {:command "my command" :version 1 :payload "{}"})
        good-checksum      (pl-utils/utf8-string->sha1 good-payload)
        bad-payload        "some test message"
        bad-checksum       (pl-utils/utf8-string->sha1 bad-payload)]
    (-> {:payload good-payload :checksum good-checksum}
      (make-request)
      (*app*))
    (-> {:payload bad-payload :checksum bad-checksum}
      (make-request)
      (*app*))

    (let [[good-msg bad-msg] (drain-into-vec! *conn* "com.puppetlabs.puppetdb.commands" 100)
          good-command       (json/parse-string good-msg true)]
      (testing "should be timestamped when parseable"
        (let [timestamp (get-in good-command [:annotations :received])]
          (time/parse (time/formatters :date-time) timestamp)))

      (testing "should be left alone when not parseable"
        (is (= bad-msg bad-payload))))))