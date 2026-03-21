(ns gridx.pricing.schema.raw
  "Malli schemas for the raw GridX Pricing API response shape.

  These mirror the JSON exactly: camelCase keys, string values.
  Covers both PG&E and SCE response formats (same structure,
  different component/priceType vocabularies).

  Most consumers should use `gridx.pricing.schema` (the coerced schemas)
  instead — these are primarily useful for boundary validation.")

(def PriceComponent
  [:map
   [:component :string]
   [:intervalPrice :string]
   [:priceType :string]])

(def PriceDetail
  [:map
   [:startIntervalTimeStamp :string]
   [:intervalPrice :string]
   [:priceStatus :string]
   [:priceComponents [:vector PriceComponent]]])

(def PriceHeader
  [:map
   [:priceCurveName :string]
   [:marketName :string]
   [:intervalLengthInMinutes [:enum 15 60]]
   [:settlementCurrency :string]
   [:settlementUnit :string]
   [:startTime :string]
   [:endTime :string]
   [:recordCount :int]])

(def PriceCurve
  [:map
   [:priceHeader PriceHeader]
   [:priceDetails [:vector PriceDetail]]])

(def ResponseMeta
  [:map
   [:code :int]
   [:requestURL [:maybe :string]]
   [:requestBody :string]
   [:response :string]])

(def PricingResponse
  [:map
   [:meta ResponseMeta]
   [:data [:vector PriceCurve]]])
