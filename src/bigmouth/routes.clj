(ns bigmouth.routes
  (:require [bigmouth.atom :as atom]
            [bigmouth.models.subscription :as subs]
            [bigmouth.utils :as utils]
            [bigmouth.webfinger :as webfinger]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [compojure.core :refer :all]
            [org.httpkit.client :as http]
            [ring.util.response :as res]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [selmer.parser :as parser]))

(defn- host-meta [{:keys [use-https? local-domain]}]
  (let [context {:use-https? use-https? :local-domain local-domain}]
    (-> (res/response (parser/render-file "host-meta" context))
        (res/content-type "application/xrd+xml; charset=utf-8"))))

(defn- webfinger [resource-uri configs]
  (let [[_ account] (re-find #"^acct:([^@]*?)@" resource-uri)
        account-resource (webfinger/account-resource account configs)]
    (-> (res/response (json/write-str account-resource))
        (res/content-type "application/jrd+json; charset=utf-8"))))

(defn make-well-known-routes [configs]
  (-> (routes
        (GET "/.well-known/host-meta" []
          (host-meta configs))
        (GET "/.well-known/webfinger" [resource]
          (webfinger resource configs)))
      (wrap-keyword-params)
      (wrap-params)))

(defn- user-feed [account configs]
  (let [entry {:id "001", :message "hogehoge"}]
    (-> (res/response (atom/atom-feed account [entry] configs))
        (res/content-type "application/atom+xml; charset=utf-8"))))

(defn- subscribe [subscription-repo params configs]
  (let [topic (get params "hub.topic")
        secret (get params "hub.secret")
        callback (get params "hub.callback")
        lease-seconds (get params "hub.lease_seconds")
        lease-seconds (if (empty? lease-seconds)
                        (* 86400 7)
                        (-> (Long/parseLong lease-seconds)
                            (min (* 86400 30))
                            (max (* 86400 7))))
        [_ account] (re-find #"/users/([^.]+?).atom$" topic)
        challenge (str (rand-int Integer/MAX_VALUE))] ;FIXME: more secure challenge message needed
    (http/get callback
              {:query-params {:hub.topic (utils/feed-url account configs)
                              :hub.mode "subscribe"
                              :hub.challenge challenge
                              :hub.lease_seconds lease-seconds}}
              (fn [{:keys [status error body]}]
                (when (and (= status 200) (not error) (= body challenge))
                  (subs/subscribe! subscription-repo account callback secret lease-seconds))))
    (res/status {} 202)))

(defn- unsubscribe [subscription-repo params]
  (prn :params params))

(defn make-mastodon-routes [subscription-repo configs]
  (-> (routes
        (GET "/users/:account.atom" [account]
          (user-feed account configs))
        (POST "/api/push" {:keys [params]}
          (if (= (get params "hub.mode") "subscribe")
            (subscribe subscription-repo params configs)
            (unsubscribe subscription-repo params configs))))
      (wrap-keyword-params)
      (wrap-params)))

(defn- if-matches [path route]
  (fn [{:keys [uri] :as req}]
    (when (str/starts-with? uri path)
      (route req))))

(defn make-bigmouth-routes [subscription-repo configs]
  (let [well-known-routes (make-well-known-routes configs)
        mastodon-routes (make-mastodon-routes subscription-repo configs)]
    (routes
      (if-matches "/.well-known"
        well-known-routes)
      mastodon-routes)))
