(ns com.vetd.app.rounds
  (:require [com.vetd.app.db :as db]
            [com.vetd.app.docs :as docs]
            [com.vetd.app.hasura :as ha]
            [com.vetd.app.proc-tree :as ptree]
            [com.vetd.app.util :as ut]))

(defn insert-round-product
  [round-id prod-id]
  (let [[id idstr] (ut/mk-id&str)]
    (-> (db/insert! :round_product
                    {:id id
                     :idstr idstr
                     :round_id round-id
                     :product_id prod-id
                     :created (ut/now-ts)
                     :updated (ut/now-ts)})
        first)))

(defn product-is-in-round?
  [product-id round-id]
  (-> [[:rounds {:id round-id}
        [:id
         [:products {:id product-id}
          [:id]]]]]
      ha/sync-query
      :rounds
      first
      :products
      seq
      boolean))

(defn invite-product-to-round
  [product-id round-id]
  (when-not (product-is-in-round? product-id round-id)
    (insert-round-product round-id product-id)))

(defn sync-round-vendor-req-forms-to-add?
  [{:keys [deleted] forms :vendor-response-form-docs}]
  (and (empty? forms)
       (nil? deleted)))

(defn sync-round-vendor-req-forms-to-remove?
  [{:keys [deleted] forms :vendor-response-form-docs}]
  (not (or (empty? forms)
           (nil? deleted))))

(defn sync-round-vendor-req-forms
  [round-id]
  (let [{:keys [buyer-id req-form-template] :as round} (-> [[:rounds {:id round-id}
                                                             [:buyer-id
                                                              [:req-form-template
                                                               [:id]]]]]
                                                           ha/sync-query
                                                           :rounds
                                                           first)
        form-template-id (:id req-form-template)
        rps (-> [[:round-product {:round-id round-id}
                  [:id :product-id :deleted
                   [:vendor-response-form-docs
                    [:id :doc-id]]
                   [:product
                    [:vendor-id]]]]]
                ha/sync-query
                :round-product)
        prod-id->exists (->> rps
                             (group-by :product-id)
                             (ut/fmap (partial some
                                               (comp nil? :deleted))))
        to-add (filter sync-round-vendor-req-forms-to-add?
                       rps)
        to-remove (filter sync-round-vendor-req-forms-to-remove?
                          rps)
        added (-> (for [{:keys [id vendor-id product]} to-add]
                    (docs/create-form-from-template {:form-template-id form-template-id
                                                     :from-org-id buyer-id
                                                     :to-org-id (:vendor-id product)
                                                     :subject id
                                                     :title (format "Round Req Form -- round %d / prod %d "
                                                                    round-id
                                                                    vendor-id)}))
                  doall)]
    (doseq [{:keys [id] forms :vendor-response-form-docs :as r} to-remove]
      (docs/update-deleted :round_product id)
      (doseq [{form-id :id doc-id :doc-id} forms]
        (when doc-id
          (docs/update-deleted :docs doc-id))
        (docs/update-deleted :forms form-id)))
    {:added (map vector added to-add)
     :to-remove to-remove}))

(defn sync-round-vendor-req-forms&docs [round-id]
  (let [{:keys [added]} (sync-round-vendor-req-forms round-id)]
    (doseq [[{:keys [id subject from-org-id to-org-id]}
             {:keys [product-id]}]
            added]
      (let [doc (docs/create-doc {:form-id id
                                  :subject subject
                                  :data {}
                                  :to-org-id from-org-id
                                  :from-org-id to-org-id})]
        (-> doc
            :item
            :id
            docs/auto-pop-missing-responses-by-doc-id)))))
