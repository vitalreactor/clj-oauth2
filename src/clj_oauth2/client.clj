(ns clj-oauth2.client
  (:refer-clojure :exclude [get])
  (:use [clj-http.client :only [wrap-request]])
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [cheshire.core :as json]
            [uri.core :as uri])
  (:import [clj_oauth2 OAuth2Exception OAuth2StateMismatchException]
           [org.apache.commons.codec.binary Base64]))

(defn make-auth-request
  [{:keys [authorization-uri client-id redirect-uri scope access-type]}
   & [state]]
  (let [uri (uri/uri->map (uri/make authorization-uri) true)
        query (assoc (:query uri)
                :client_id client-id
                :redirect_uri redirect-uri
                :response_type "code")
        query (if state (assoc query :state state) query)
        query (if access-type (assoc query :access_type access-type) query)
        query (if scope
                (assoc query :scope (str/join " " scope))
                query)]
    {:uri (str (uri/make (assoc uri :query query)))
     :scope scope
     :state state}))

(defn- add-auth-header [req scheme param] ; Force.com
  (let [header (str scheme " " param)]
    (assoc-in req [:headers "Authorization"] header)))

(defn- add-base64-auth-header [req scheme param]
  (add-auth-header req scheme (Base64/encodeBase64String (.getBytes param))))

(defmulti prepare-access-token-request
  (fn [request endpoint params]
    (name (:grant-type endpoint))))

(defmethod prepare-access-token-request
  "authorization_code" [request endpoint params]
  (merge-with merge request
              {:body {:code
                      (:code params)
                      :redirect_uri
                      (:redirect-uri endpoint)}}))

(defmethod prepare-access-token-request
  "password" [request endpoint params]
  (merge-with merge request
              {:body {:username (:username params)
                      :password (:password params)}}))

(defn- add-client-authentication [request endpoint]
  (let [{:keys [client-id client-secret authorization-header?]} endpoint]
    (if authorization-header?
      (add-base64-auth-header
       request
       "Basic"
       (str client-id ":" client-secret))
      (merge-with
       merge
       request
       {:body
        {:client_id client-id
         :client_secret client-secret}}))))

(defn read-json-from-body
  "convert body to a reader to be compatible with clojure.data.json 0.2.1
   In case body is a byte array, aka class [B"
  [body]
  (if (instance? String body)
    (json/parse-string body true)
    (with-open [reader (clojure.java.io/reader body)]
      (json/parse-stream reader true))))

(defn- request-access-token
  [endpoint params]
  (let [{:keys [access-token-uri access-query-param grant-type]} endpoint
        request
        {:content-type "application/x-www-form-urlencoded"
         :throw-exceptions false
         :body {:grant_type grant-type}}
        request (prepare-access-token-request request endpoint params)
        request (add-client-authentication request endpoint)
        request (update-in request [:body] uri/form-url-encode)
        {:keys [body headers status]} (http/post access-token-uri request)
        content-type (headers "content-type")
        body (if (and content-type
                      (or (.startsWith content-type "application/json")
                          (.startsWith content-type "text/javascript"))) ; Facebookism
               (read-json-from-body body)
               (uri/form-url-decode body)) ; Facebookism
        error (:error body)]
    (if (or error (not= status 200))
      (throw (OAuth2Exception. (if error
                                 (if (string? error)
                                   (:error_description body)
                                   (:message error)) ; Facebookism
                                 "error requesting access token")
                               (if error
                                 (if (string? error)
                                   error
                                   (:type error)) ; Facebookism
                                 "unknown")))
      {:access-token (:access_token body)
       :token-type (or (:token_type body) "draft-10") ; Force.com
       :query-param access-query-param
       :params (dissoc body :access_token :token_type)
       :refresh-token (:refresh_token body)})))

(defn get-access-token
  [endpoint
   & [params {expected-state :state expected-scope :scope}]]
  (let [{:keys [state error]} params]
    (cond (string? error)
          (throw (OAuth2Exception. (:error_description params) error))
          (and expected-state (not (= state expected-state)))
          (throw (OAuth2StateMismatchException.
                  (format "Expected state %s but got %s"
                          state expected-state)
                  state
                  expected-state))
          :else
          (request-access-token endpoint params))))

(defn with-access-token [uri {:keys [access-token query-param]}]
  (str (uri/make (assoc-in (uri/uri->map (uri/make uri) true)
                           [:query query-param]
                           access-token))))

(defmulti add-access-token-to-request
  (fn [req oauth2]
    (:token-type oauth2)))

(defmethod add-access-token-to-request
  :default [req oauth2]
  (let [{:keys [token-type]} oauth2]
    (if (:throw-exceptions req)
      (throw (OAuth2Exception. (str "Unknown token type: " token-type)))
      [req false])))

(defmethod add-access-token-to-request
  "bearer" [req oauth2]
  (let [{:keys [access-token query-param]} oauth2]
    (if access-token
      [(if query-param
         (assoc-in req [:query-params query-param] access-token)
         (add-auth-header req "Bearer" access-token))
       true]
      [req false])))

(defmethod add-access-token-to-request ; Force.com
  "draft-10" [req oauth2]
  (let [{:keys [access-token query-param]} oauth2]
    (if access-token
      [(if query-param
         (assoc-in req [:query-params query-param] access-token)
         (add-auth-header req "OAuth" access-token))
       true]
      [req false])))

(defn wrap-oauth2 [client]
  (fn [req]
    (let [{:keys [oauth2 throw-exceptions]} req
          [req token-added?] (add-access-token-to-request req oauth2)
          req (dissoc req :oauth2)]
      (if token-added?
        (client req)
        (if throw-exceptions
          (throw (OAuth2Exception. "Missing :oauth2 params"))
          (client req))))))

(defn refresh-access-token
  [refresh-token {:keys [client-id client-secret access-token-uri]}]
  (let [req (http/post access-token-uri {:form-params
                                         {:client_id client-id
                                          :client_secret client-secret
                                          :refresh_token refresh-token
                                          :grant_type "refresh_token"}})]
    (when (= (:status req) 200)
      (read-json-from-body (:body req) true))))

(def request
  (wrap-oauth2 http/request))

(defmacro def-request-shortcut-fn [method]
  (let [method-key (keyword method)]
    `(defn ~method [url# & [req#]]
       (request (merge req#
                       {:method ~method-key
                        :url url#})))))

(def-request-shortcut-fn get)
(def-request-shortcut-fn post)
(def-request-shortcut-fn put)
(def-request-shortcut-fn delete)
(def-request-shortcut-fn head)
