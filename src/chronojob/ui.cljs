(ns chronojob.ui
  (:require [rum.core :as rum]
            [goog.events]
            [cljs.core.async :as a]
            [cljs-http.client :as http]
            [goog.Uri :as Uri]
            [plumbing.core :refer [assoc-when]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:import [goog.history Html5History EventType]
           [goog.date Date]))

(enable-console-print!)

(defn set-state-from-history
  [state history]
  (let [uri (Uri/parse (.getToken history))]
    (assoc state
           :selected-tags (set (js->clj (.getParameterValues uri "tags")))
           :status (.getParameterValue uri "status")
           :search (.getParameterValue uri "search"))))

(defonce astate (atom {:tags #{}
                       :selected-tags #{}
                       :status nil
                       :search nil
                       :body nil
                       :stats nil
                       :jobs nil
                       :job-details nil}))

(defn handle-url-change [history e]
  (swap! astate set-state-from-history history))

(defn make-history []
  (let [history (Html5History.)]
    (doto history
      (goog.events/listen EventType.NAVIGATE
                          #(handle-url-change history %))
      ;; (.setPathPrefix "/")
      ;; (.setUseFragment false)
      (.setEnabled true))))

(defonce history (make-history))

(defn set-url
  []
  (let [uri (Uri/parse "/")]
    (.setParameterValues uri "tags" (clj->js (:selected-tags @astate)))
    (when-let [v (:search @astate)] (.setParameterValue uri "search" (clj->js v)))
    (when-let [v (:status @astate)] (.setParameterValue uri "status" (clj->js v)))
    (.setToken history (str uri))))

(defn receive-stats
  []
  (go
    (let [res (a/<! (http/get "dashboard/stats" {:query-params {:tags (:selected-tags @astate)}}))]
      (when (= 200 (:status res))
        (swap! astate assoc :stats (:body res))))))

(defn receive-tags
  []
  (go
    (let [res (a/<! (http/get "dashboard/tags"))]
      (when (= 200 (:status res))
        (swap! astate assoc :tags (set (get-in res [:body :tags])))))))

(defn receive-jobs
  []
  (go
    (let [res (a/<! (http/get "dashboard/jobs"
                              {:query-params (assoc-when {:tags (:selected-tags @astate)}
                                                         :search (:search @astate)
                                                         :status (:status @astate))}))]
      (when (= 200 (:status res))
        (swap! astate assoc :jobs (set (get-in res [:body :rows])))))))

(rum/defc stats < rum/static
  [stats]
  (let [{:keys [total last-hour last-24-hours]} stats]
    [:div.row
     [:div.col-md-4
      [:div.panel.panel-default
       [:div.panel-body
        [:ul.list-group
         [:li.list-group-item.list-group-item-info "pending" [:span.badge (:pending total "-")]]
         [:li.list-group-item.list-group-item-warning "redo" [:span.badge (:redo total "-")]]]]]]
     [:div.col-md-4
      [:div.panel.panel-default
       [:div.panel-heading
        "LAST HOUR"]
       [:div.panel-body
        [:ul.list-group
         [:li.list-group-item.list-group-item-success "completed" [:span.badge (:completed last-hour "-")]]
         [:li.list-group-item.list-group-item-danger "failed" [:span.badge (:failed last-hour "-")]]]]]]
     [:div.col-md-4
      [:div.panel.panel-default
       [:div.panel-heading
        "LAST 24 HOURS"]
       [:div.panel-body
        [:ul.list-group
         [:li.list-group-item.list-group-item-success "completed" [:span.badge (:completed last-24-hours "-")]]
         [:li.list-group-item.list-group-item-danger "failed" [:span.badge (:failed last-24-hours "-")]]]]]]]))

(defn toggle-tag
  [tag]
  (swap! astate update-in [:selected-tags]
         (if ((:selected-tags @astate) tag) disj conj)
         tag)
  (set-url)
  (receive-stats)
  (receive-jobs))

(rum/defc tags < rum/static
  [tags selected]
  [:div.row
   [:div.col-md-12
    [:ul.nav.nav-pills
     (for [t tags]
       [:li {:class (when (selected t) "active")
             :key t}
        [:a {:on-click #(toggle-tag t) } t]])]]])

(defn set-job-details [job]
  (swap! astate assoc :job-details job))

(rum/defc jobs < rum/static
     [jobs]
     [:div.row
      [:div.col-md-12
       [:table.table.table-striped
        [:thead
         [:tr
          [:th "id"]
          [:th "status"]
          [:th "tags"]
          [:th "created at"]
          [:th "run at"]
          [:th "body"]]]
        [:tbody
         (for [{:keys [id status created_at do_at tags job]} jobs]
           [:tr {:key id}
            [:td id]
            [:td [:span.label {:class (case status
                                        "completed" "label-success"
                                        "failed" "label-danger"
                                        "redo" "label-warning"
                                        "pending" "label-info")}
                  status]]
            [:td (interpose ", " (for [t tags] [:a {:key t :on-click #(toggle-tag t)} t]))]
            [:td created_at]
            [:td do_at]
            [:td [:a {:on-click #(set-job-details job)} "show..."]]])]]]])

(defn set-search [e]
  (let [v (.. e -target -value)]
    (swap! astate assoc :search (not-empty v))
    (set-url)
    (receive-jobs)))

(defn set-status [status]
  (swap! astate assoc :status status)
  (set-url)
  (receive-jobs))

(rum/defc statuses < rum/static
  [status]
  (let [statuses [[nil "All" "default"]
                  ["pending" "Pending" "info"]
                  ["redo" "Redo" "warning"]
                  ["failed" "Failed" "danger"]
                  ["completed" "Completed" "success"]]]
    [:div.row
     [:div.col-md-12
      [:div.btn-group.btn-group-justified
       (for [[state title class] statuses]
         [:a.btn {:key title
                  :on-click #(set-status state)
                  :class [(str "btn-" class)
                          (when (= state status) "active")]}
          title])]]]))

(rum/defc job-details < rum/static
  [details]
  (when details
    [:div.modal.fade.in {:style {:display "block" :padding-left "0px"}}
     [:div.modal-dialog
      [:div.modal-content
       [:div.modal-header [:button.close {:type "button" :on-click #(set-job-details nil)} [:span "×"]]]
       [:div.modal-body [:pre (.stringify js/JSON (clj->js details) nil "\t")]]]]]))

(rum/defc main < rum/static
  [state]
  [:div.container
   (tags (:tags state) (:selected-tags state))
   [:div.row [:div.col-md-12 [:hr]]]
   (stats (:stats state))
   (statuses (:status state))
   [:div.row [:div.col-md-12 [:hr]]]
   [:div.row
    [:div.col-md-6
     [:a {:on-click receive-jobs} [:span.glyphicon.glyphicon-refresh]]]
    [:div.col-md-6.text-right
     [:form.form-inline
      [:div.form-group
       [:div.input-group
        [:input.form-control {:type "text" :placeholder "Search body"
                              :default-value (:search state)
                              :on-blur #(set-search %)}]
        [:div.input-group-addon [:span.glyphicon.glyphicon-search]]]]]]]
   (job-details (:job-details state))
   (jobs (:jobs state))])

(rum/defc wrapper
  []
  (main @astate))

(defonce root nil)

(defn render
  []
  (let [component (rum/mount (wrapper) (js/document.getElementById "application"))]
    (set! root component)
    (add-watch astate :render
               (fn [_ _ _ _]
                 (rum/request-render component)))))

(defn init-controller
  []
  (receive-tags)
  (receive-jobs)
  (go-loop []
    (a/<! (receive-stats))
    (a/<! (a/timeout 10000))
    (recur)))

(defn ^:export init
  []
  (render)
  (init-controller))

(defn ^:export reload-hook
  []
  (rum/request-render root))
