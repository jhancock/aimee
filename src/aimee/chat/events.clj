(ns aimee.chat.events)

(defn make-event
  "Create a consistent event map for chat completion channel communication."
  [kind payload]
  (case kind
    :complete {:event :complete :data payload}
    :error {:event :error :data payload}
    :chunk {:event :chunk :data payload}
    {:event kind :data payload}))
