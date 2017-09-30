(ns netrunner.account
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [sablono.core :as sab :include-macros true]
            [cljs.core.async :refer [chan put!] :as async]
            [clojure.string :as s]
            [goog.dom :as gdom]
            [netrunner.auth :refer [authenticated avatar] :as auth]
            [netrunner.appstate :refer [app-state]]
            [netrunner.ajax :refer [POST GET]]))

(def alt-arts-channel (chan))
(defn load-alt-arts []
  (go (let [alt_info (->> (<! (GET "/data/altarts"))
                       (:json)
                       (map #(select-keys % [:version :name])))
            cards (->> (:cards @app-state)
                    (filter :alt_art)
                    (map #(select-keys % [:title :setname :code :alt_art :replaces :replaced_by]))
                    (map #(if (or (contains? % :replaces)
                                  (contains? % :replaced_by))
                            (update % :title (fn [t] (str t " (" (:setname %) ")")))
                            %))
                    (into {} (map (juxt :code identity))))]
        (swap! app-state assoc :alt-arts cards)
        (swap! app-state assoc :alt-info alt_info)
        (put! alt-arts-channel cards))))

(defn image-url [card-code version]
  (let [cards (:cards @app-state)
        card (first (filter #(= card-code (:code %)) cards))
        version-path (get (:alt_art card) (keyword version) (:code card))]
    (str "/img/cards/" version-path ".png")))

(defn all-alt-art-types
  []
  (conj
    (map :version (:alt-info @app-state))
    "default"))

(defn alt-art-name
  [version]
  (let [alt (first (filter #(= (name version) (:version %)) (:alt-info @app-state)))]
    (get alt :name "Official")))

(defn handle-post [event owner url ref]
  (.preventDefault event)
  (om/set-state! owner :flash-message "Updating profile...")
  (swap! app-state assoc-in [:options :sounds] (om/get-state owner :sounds))
  (swap! app-state assoc-in [:options :background] (om/get-state owner :background))
  (swap! app-state assoc-in [:options :show-alt-art] (om/get-state owner :show-alt-art))
  (swap! app-state assoc-in [:options :sounds-volume] (om/get-state owner :volume))
  (swap! app-state assoc-in [:options :blocked-users] (om/get-state owner :blocked-users))
  (swap! app-state assoc-in [:options :alt-arts] (om/get-state owner :alt-arts))
  (.setItem js/localStorage "sounds" (om/get-state owner :sounds))
  (.setItem js/localStorage "sounds_volume" (om/get-state owner :volume))

  (let [params (:options @app-state)]
    (go (let [response (<! (POST url params :json))]
          (if (= (:status response) 200)
            (om/set-state! owner :flash-message "Profile updated - Please refresh your browser")
            (case (:status response)
              401 (om/set-state! owner :flash-message "Invalid login or password")
              421 (om/set-state! owner :flash-message "No account with that email address exists")
              :else (om/set-state! owner :flash-message "Profile updated - Please refresh your browser")))))))

(defn add-user-to-block-list
  [owner user]
  (let [blocked-user-node (om/get-node owner "block-user-input")
        blocked-user (.-value blocked-user-node)
        my-user-name (:username user)
        current-blocked-list (om/get-state owner :blocked-users)]
    (set! (.-value blocked-user-node) "")
    (when (and (not (s/blank? blocked-user))
               (not= my-user-name blocked-user)
               (= -1 (.indexOf current-blocked-list blocked-user)))
      (om/set-state! owner :blocked-users (conj current-blocked-list blocked-user)))))

(defn remove-user-from-block-list
  [evt owner]
  (let [currElt (.-currentTarget evt)
        next-sib (gdom/getNextElementSibling currElt)
        user-name (gdom/getTextContent next-sib)
        current-blocked-list (om/get-state owner :blocked-users)]
    (when user-name
      (om/set-state! owner :blocked-users (vec (remove #(= % user-name) current-blocked-list))))))

(defn select-card
  [owner value]
  (om/set-state! owner :alt-card value)
  (om/set-state! owner :alt-card-version
                 (get (om/get-state owner :alt-arts) (keyword value) "default")))

(defn set-card-art
  [owner value]
  (om/set-state! owner :alt-card-version value)
  (om/update-state! owner [:alt-arts]
                    (fn [m] (assoc m (keyword (om/get-state owner :alt-card)) value))))

(defn reset-card-art
  [owner]
  (om/set-state! owner :alt-arts {})
  (let [select-node (om/get-node owner "all-art-select")
        selected (keyword (.-value select-node))]
    (when (not= :default selected)
      (doseq [card (vals (:alt-arts @app-state))]
        (let [versions (keys (:alt_art card))]
          (when (some (fn [i] (= i (keyword selected))) versions)
            (om/update-state! owner [:alt-arts]
                              (fn [m] (assoc m (keyword (:code card)) (name selected))))))))))

(defn account-view [user owner]
  (reify
    om/IInitState
    (init-state [this] {:flash-message ""})

    om/IWillMount
    (will-mount [this]
      (om/set-state! owner :background (get-in @app-state [:options :background]))
      (om/set-state! owner :sounds (get-in @app-state [:options :sounds]))
      (om/set-state! owner :show-alt-art (get-in @app-state [:options :show-alt-art]))
      (om/set-state! owner :volume (get-in @app-state [:options :sounds-volume]))
      (om/set-state! owner :blocked-users (sort (get-in @app-state [:options :blocked-users] [])))
      (go (while true
            (let [cards (<! alt-arts-channel)
                  first-alt (first (sort-by :title (vals cards)))]
              (om/set-state! owner :alt-arts (get-in @app-state [:options :alt-arts]))
              (om/set-state! owner :alt-card (:code first-alt))
              (om/set-state! owner :alt-card-version (get-in @app-state [:options :alt-arts (keyword (:code first-alt))]
                                                             "default"))))))

    om/IRenderState
    (render-state [this state]
      (sab/html
        [:div.container
         [:div.account
          [:div.panel.blue-shade.content-page#profile-form {:ref "profile-form"}
           [:h2 "Settings"]
           [:form {:on-submit #(handle-post % owner "/update-profile" "profile-form")}
            [:section
             [:h3 "Avatar"]
             (om/build avatar user {:opts {:size 38}})
             [:a {:href "http://gravatar.com" :target "_blank"} "Change on gravatar.com"]]

            [:section
             [:h3 "Sounds"]
             [:div
              [:label [:input {:type "checkbox"
                               :value true
                               :checked (om/get-state owner :sounds)
                               :on-change #(om/set-state! owner :sounds (.. % -target -checked))}]
               "Enable sounds"]]
             [:div "Volume"
              [:input {:type "range"
                       :min 1 :max 100 :step 1
                       :on-change #(om/set-state! owner :volume (.. % -target -value))
                       :value (om/get-state owner :volume)
                       :disabled (not (om/get-state owner :sounds))}]]]

            [:section
             [:h3  "Game board background"]
             (for [option [{:name "The Root"        :ref "lobby-bg"}
                           {:name "Freelancer"      :ref "freelancer-bg"}
                           {:name "Mushin No Shin"  :ref "mushin-no-shin-bg"}
                           {:name "Traffic Jam"     :ref "traffic-jam-bg"}
                           {:name "Rumor Mill"      :ref "rumor-mill-bg"}
                           {:name "Find The Truth"  :ref "find-the-truth-bg"}
                           {:name "Push Your Luck"  :ref "push-your-luck-bg"}
                           {:name "Apex"            :ref "apex-bg"}
                           {:name "Monochrome"      :ref "monochrome-bg"}]]
               [:div.radio
                [:label [:input {:type "radio"
                                 :name "background"
                                 :value (:ref option)
                                 :on-change #(om/set-state! owner :background (.. % -target -value))
                                 :checked (= (om/get-state owner :background) (:ref option))}]
                 (:name option)]])]

            [:section {:id "alt-art"}
             [:h3 "Alt arts"]
             [:div
              [:label [:input {:type "checkbox"
                               :name "show-alt-art"
                               :checked (om/get-state owner :show-alt-art)
                               :on-change #(om/set-state! owner :show-alt-art (.. % -target -checked))}]
               "Show alternate card arts"]]

             (when (and (:special user) (:alt-arts @app-state))
               [:div {:id "my-alt-art"}
                [:h4 "My alternate card arts"]
                [:select {:on-change #(select-card owner (.. % -target -value))}
                 (for [card (sort-by :title (vals (:alt-arts @app-state)))]
                   [:option {:value (:code card)} (:title card)])]

                [:div {:class "alt-art-group"}
                 (for [version (conj (keys (get-in (:alt-arts @app-state) [(om/get-state owner :alt-card) :alt_art])) :default)]
                   (let [url (image-url (om/get-state owner :alt-card) version)]
                     [:div
                      [:div
                       [:div [:label [:input {:type "radio"
                                              :name "alt-art-radio"
                                              :value (name version)
                                              :on-change #(set-card-art owner (.. % -target -value))
                                              :checked (= (om/get-state owner :alt-card-version) (name version))}]
                              (alt-art-name version)]]]
                      [:div
                       [:img {:class "alt-art-select"
                              :src url
                              :on-click #(set-card-art owner (name version))
                              :onError #(-> % .-target js/$ .hide)
                              :onLoad #(-> % .-target js/$ .show)}]]]))]
               [:div {:id "set-all"}
                "Reset all cards to: "
                [:select {:ref "all-art-select"}
                 (for [t (all-alt-art-types)]
                   [:option {:value t} (alt-art-name t)])]
                [:button
                 {:type "button"
                  :on-click #(do
                               (reset-card-art owner)
                               (select-card owner (om/get-state owner :alt-card)))}
                 "Reset"]]])]

            [:section
             [:h3 "Blocked users"]
             [:div
              [:input.search {:on-key-down (fn [e]
                                             (when (= e.keyCode 13)
                                               (do
                                                 (add-user-to-block-list owner user)
                                                 (.preventDefault e))))
                              :ref "block-user-input"
                              :type "text" :placeholder "User name"}]
              [:button.block-user-btn {:type "button"
                                       :name "block-user-button"
                                       :on-click #(add-user-to-block-list owner user)}
               "Block user"]]
             (for [bu (om/get-state owner :blocked-users)]
               [:div.line
                [:button.small.unblock-user {:type "button"
                                             :on-click #(remove-user-from-block-list % owner)} "X" ]
                [:span.blocked-user-name (str "  " bu)]])]

            [:p {:id "update"}
             [:button "Update Profile"]
             [:span.flash-message (:flash-message state)]]]]]]))))


(defn account [{:keys [user]} owner]
  (om/component
   (when user
     (om/build account-view user))))

(om/root account app-state {:target (. js/document (getElementById "account"))})
