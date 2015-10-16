(ns parinfer.format.infer
  "Corrects parens based on indentation.
  (used while editing a file)"
  (:require
    [clojure.string :refer [join]]
    [parinfer.format.string :refer [insert-string
                                    remove-str-range]]
    [parinfer.format.reader :refer [push-char
                                    whitespace?
                                    escaping?
                                    in-str?
                                    in-code?
                                    in-comment?
                                    valid-closer?
                                    matching-delim
                                    closing-delim?]]))

(def initial-state
  "An initial state of our running state."
  {:lines []                           ;; final lines containing the inferenced closing delimiters.
   :line-no -1                         ;; current line number we are processing.
   :track-indent? false                ;; "true" when we are looking for the first char on a line to signify indentation.
   :delim-trail {:start nil :end nil}  ;; track EOL delims since we replace them wholesale with inferred delims.
   :insert {:line-no nil :x-pos nil}   ;; the place to insert closing delimiters whenever we hit appropriate indentation.
   :stack []                           ;; the delimiter stack, maps of [:x-pos :ch :indent-delta]
   :backup []                          ;; trailing delims that are pushed back onto the stack at EOL
   })

(defn close-delims
  "Update the state by inferring closing delimiters.
  Do this by using the given indentation level.
  
  Example:
  
  (defn foo [a b
     ret           ;; <---  When we process `r`, we detect indentation, then...

  (defn foo [a b]  ;; <---  ... we insert a `]` after `b` since `[` is after `r` on the x-axis.
     ret           ;; <---  A `)` is inserted after `ret` if no further indented lines found.
  "
  ([state] (close-delims state 0))
  ([state indent-x]

   (let [;; Pop the appropriate delims off the stack, and create the inferred delim string.
         [stack delims]
         (loop [stack (:stack state), delims ""]
           (if-not (seq stack)
             [stack delims]
             (let [{:keys [x-pos ch]} (peek stack)]
               (if (>= x-pos indent-x)
                 (recur (pop stack) (str delims (matching-delim ch)))
                 [stack delims]))))

         ;; Insert the delims in the correct place, then update the state
         {:keys [line-no x-pos]} (:insert state)
         state (-> state
                    (update-in [:lines line-no] insert-string x-pos delims)
                    (assoc :track-indent? false
                           :stack stack))]
     state)))

(defn update-delim-trail
  "Update the state's delim trail as we scan across a line.
  We eventually remove the delim trail since we indented
  content below can cause the delims to move.

  Example:
  
  (foo (+ 2 3) [(bar)] )    ;; a potential comment
                    ^^^^
                     |
                     +-- trailing delims that we will remove
                          (notice whitespace will also be removed)
  "
  [{:keys [stack delim-trail backup x-pos ch cursor-line line-no cursor-x cursor-in-comment?] :as state}]
  (let [ 

        ;; these characters won't block, unless they're escaped
        pass-char? (or (= ";" ch)
                     (whitespace? ch)
                     (closing-delim? ch))

        ;; must be in code (before push-char)
        reset? (when (in-code? stack)
                 (or (escaping? stack)
                     (not pass-char?)))

        cursor-in-comment? (or cursor-in-comment?
                               (and (= cursor-line line-no)
                                    (= x-pos cursor-x)
                                    (in-comment? stack)))

        ;; Determine if we have a delimiter we can track.
        update? (and (in-code? stack)
                     (not (escaping? stack))
                     (closing-delim? ch)
                     (valid-closer? stack ch))

        ;; Clear the backup delimiters if we reset.
        backup (cond-> backup reset? empty)

        ;; Update the delimiter trail range if needed.
        delim-trail (cond
                      reset? {}
                      update? (-> delim-trail
                                  (update-in [:start] #(or % x-pos))
                                  (assoc :end (inc x-pos)))
                      :else delim-trail)]

    (assoc state
      :cursor-in-comment? cursor-in-comment?
      :backup backup
      :delim-trail delim-trail)))

(defn block-delim-trail
  "The presence of the cursor can block the removal of some part of the delim trail."
  [{:keys [delim-trail line-no cursor-line cursor-x cursor-in-comment?] :as state}]
  (let [{:keys [start end]} delim-trail
        cursor-block? (and (= line-no cursor-line)
                           start
                           (> cursor-x start)
                           (not cursor-in-comment?))
        start (cond-> start (and start cursor-block?) (max cursor-x))
        end (cond-> end (and end cursor-block?) (max cursor-x))
        [start end] (when-not (= start end) [start end])]
    (assoc state
           :delim-trail {:start start :end end})))

(defn remove-delim-trail
  "Update the state by removing our marked delim trail.
  We remove the delims from the appropriate line of text,
  while also restoring their matching delims onto the stack.

  Example:
  
  (foo (+ 2 3) [(bar)] )    ;; a potential comment
  ^            ^^   ^^^^
  |            |     |
  |____________|     +-- Remove these from the text.
         |
         +-- Restore these onto the delim stack.
             (fyi, we originally popped them off to validate
              the closing delims. now we need them back to
              infer closing delims for indented lines.)
  "
  [{:keys [delim-trail insert line-no lines backup stack] :as state}]
  (let [{:keys [start end]} delim-trail]
    (if (and start end)
      (let [line (get lines line-no)
            delims (->> (subs line start end)
                        (map str)
                        (filter closing-delim?))
            remove-count (count delims)
            ignore-count (- (count backup) remove-count)
            [backup stack] (loop [backup backup, stack stack]
                             (if (= ignore-count (count backup))
                               [backup stack]
                               (recur (pop backup) (conj stack (peek backup)))))
            state (-> state
                       (update-in [:lines line-no] remove-str-range start end)
                       (assoc :backup backup
                              :stack stack
                              :removed-delims delims))

            insert-line? (= (:line-no insert) line-no)
            state (cond-> state
                     insert-line? (update-in [:insert :x-pos] min start))]
        state)
      state)))

(defn update-insertion-pt
  "Update the state's trailing delimiter insertion point as we scan the line.
  
  Example:
  
  (defn foo [a b] ret)
  ^^^^^ ^^^ ^^ ^  ^^^
                    |
                    +-- final insertion point candidate

  Special rules allow the user to freely position the trailing
  delimiters while editing a line.

  "
  [{:keys [track-indent? cursor-line lines line-no stack x-pos ch] :as state}]
  (let [prev-ch (str (last (get lines line-no)))

        insert-at-char? (and 
                          ;; must be in code (after push-char)
                          (in-code? stack)

                          ;; don't insert at blank (a removed character)
                          (not= "" ch)

                          ;; don't insert at whitespace, unless escaped
                          (or (not (whitespace? ch))
                              (= "\\" prev-ch))

                          ;; don't insert at closing delim, unless cursor is on this line
                          (or (not (closing-delim? ch))
                              (= line-no cursor-line)))

        ;; Add potential insert point for closing delimiters if required.
        insert (when insert-at-char?
                 {:line-no line-no
                  :x-pos (inc x-pos)})]

    (cond-> state
      insert (assoc :insert insert))))

(defn process-indent
  "Update the state by handling a possible indentation trigger.

  Example:
  
  (defn foo [a b
     ret           ;; <---  When we process `r`, we detect indentation, then
                   ;;       we start backtracking to insert closing delimiters on a previous line.


  (defn foo [a b]
     )             ;; <---  If a line starts with a closing delimiter, it is not
                   ;;       considered an indentation trigger.  In fact, we skip
                   ;;       the character completely, removing it from the line.
  "
  [{:keys [stack track-indent? lines line-no x-pos ch] :as state}]
  (let [check-indent? (and track-indent?
                        (in-code? stack)
                        (not (whitespace? ch))
                        (not= ";" ch))
        skip? (and check-indent? (closing-delim? ch))
        at-indent? (and check-indent? (not skip?))
        state (assoc state :process? (not skip?))]
    (cond-> state
      at-indent? (close-delims x-pos))))

(defn update-line
  "Update the state by addding processed character to the line."
  [{:keys [ch line-no] :as state}]
  (update-in state [:lines line-no] str ch))

(defn process-char*
  [state]
  ;; NOTE: the order here is important!
  (-> state
      update-delim-trail
      push-char
      update-insertion-pt
      update-line))

(defn process-char
  "Update the state by processing the given character and its position."
  [{:keys [lines line-no] :as state} ch]
  (let [x-pos (count (get lines line-no))
        state (assoc state :x-pos x-pos :ch (str ch))
        state (process-indent state)]
    (cond-> state
      (:process? state) process-char*)))

(defn process-line
  "Update the state by processing the given line of text."
  ([line] (process-line initial-state line))
  ([{:keys [stack lines line-no cursor-line] :as state} line]
   (let [line-no (inc line-no)
         state (assoc state
                  :backup []
                  :cursor-in-comment? false
                  :delim-trail {:start nil :end nil}
                  :track-indent? (and (seq stack) (not (in-str? stack)))
                  :lines (conj lines "")
                  :line-no line-no
                  :removed-delims [])
         state (reduce process-char state (str line "\n"))
         state (-> state
                   block-delim-trail
                   remove-delim-trail)]
     state)))

(defn process-text
  "Update the state by processing the given text."
  ([text] (process-text initial-state text))
  ([state text]
   (let [state (merge initial-state state)
         lines (.split text "\n") ;; different from clojure.string/split (respects empty lines)
         state (reduce process-line state lines)
         stack (:stack state)]

     ;; VERY IMPORTANT!!!!
     ;; Return nil if there is an unclosed string, meaning we must cancel
     ;; processing the text.  If we don't this, the simple act of typing two
     ;; quotes in succession could silently delete delimiters in subsequent
     ;; strings.
     (when-not (in-str? stack)
       (cond-> state (seq stack) close-delims)))))

(defn format-text
  "Format the given text by repositioning any trailing closing delimiters based on indentation."
  ([text] (format-text initial-state text))
  ([state text]
   (if-let [state (process-text state text)]
     (join "\n" (:lines state))
     text)))
