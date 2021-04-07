;; # Hello observator!!! 👋
(ns observator.core
  (:require [clojure.string :as str]
            [observator.lib :as obs.lib]
            [observator.hashing :as hashing]
            [nextjournal.beholder :as beholder]
            [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]
            [datoteka.core :as fs]))

;; TODO
;; * fix issue when var is renamed: hash stays the same, dependents don't see new name
;; * identify symbols that point to libraries, first for Clojure, e.g. `{rewrite-clj.parser rewrite-clj/rewrite-clj}`
;; * research mapping of requires to library coords (no mapping, look into jar)

;; plot things with aeroic
;; recurse with try block but return an identifiable error

(defn fix-case [s]
  (obs.lib/fix-case s))

;; **Dogfooding** the system while constructing it, I'll try to make a
;; little bit of literate commentary. This is *literate* programming.
(def slow-thing
  (do
    (Thread/sleep 500)
    (map fix-case (str/split-lines (slurp "/usr/share/dict/words")))))

(count slow-thing)

(do ;; slow as well
  (Thread/sleep 500)
  42)

(def ^:observator/no-cache random-thing
  (rand-int 1000))

(def random-cached-thing
  (rand-int 1000))

(def md->html
  "Convert markdown to HTML."
  (let [md-parser (.build (org.commonmark.parser.Parser/builder))
        html-renderer (.build (org.commonmark.renderer.html.HtmlRenderer/builder))]
    (fn [md]
      (.render html-renderer (.parse md-parser md)))))

;; The following two functions determine the typographic conventions
;; for the viewer.

(defn make-syntax-pane
  "Create a new syntax-highlighting enabled text area set up for Clojure code."
  ([code]
   (make-syntax-pane code nil))
  ([code {:keys [background?]}]
   (doto (javax.swing.JScrollPane.
          (let [textarea (org.fife.ui.rsyntaxtextarea.RSyntaxTextArea. code (inc (count (clojure.string/split-lines code))) 80)]
            (when background?
              (.setBackground textarea (java.awt.Color. 245 245 245)))
            (doto textarea
              (.setPreferredSize (java.awt.Dimension. (count (clojure.string/split-lines code)) 80))
              (.setFont (java.awt.Font. "Fira Code" java.awt.Font/PLAIN 16))
              (.setSyntaxEditingStyle org.fife.ui.rsyntaxtextarea.SyntaxConstants/SYNTAX_STYLE_CLOJURE)
              (.setHighlightCurrentLine false)
              (.setBorder (javax.swing.BorderFactory/createEmptyBorder 12 12 12 12))
              ;;(.setBorder (javax.swing.border.LineBorder. java.awt.Color/black))
              (.setEditable false))))
     (.setBorder (javax.swing.BorderFactory/createEmptyBorder))
     (.setVerticalScrollBarPolicy javax.swing.ScrollPaneConstants/VERTICAL_SCROLLBAR_NEVER))))

(defn make-html-pane
  "Create a new text area that understands basic HTML formatting and looks not completely terrible."
  [html]
  (doto (javax.swing.JScrollPane.
         (doto (javax.swing.JTextPane.)
           (.setPreferredSize (java.awt.Dimension. (inc (count (clojure.string/split-lines html))) 80))
           (.putClientProperty javax.swing.JEditorPane/HONOR_DISPLAY_PROPERTIES true)
           (.setFont (java.awt.Font. "Georgia" java.awt.Font/PLAIN 20))
           (.setContentType "text/html")
           (.setText html)
           ;; (.setBorder (javax.swing.border.LineBorder. java.awt.Color/black))
           (.setEditable false)))
    (.setBorder (javax.swing.BorderFactory/createEmptyBorder))))

;; These next two definitions should not be global, but it is
;; convenient for them to be so during development. Ultimately, we
;; probably want multiple frame support to watch multiple files.

(defonce panel
  (let [p (javax.swing.JPanel.)]
    (doto p
      (.setBackground java.awt.Color/WHITE)
      (.setBorder (javax.swing.BorderFactory/createEmptyBorder 12 12 12 12))
      (.setLayout (javax.swing.BoxLayout. p javax.swing.BoxLayout/Y_AXIS)))))

(defonce frame
  (let [frame (javax.swing.JFrame.)]
    (.add (.getContentPane frame)
          (javax.swing.JScrollPane. panel))
    (doto frame
      (.pack)
      (.setSize 800 1200)
      (.setVisible true))))

(defn remove-leading-semicolons [s]
  (clojure.string/replace s #"^[;]+" ""))


(defonce !var->hash
  (atom {}))

(defn read+eval-cached [code-string]
  (let [cache-dir (str fs/*cwd* fs/*sep* ".cache")
        form (-> code-string read-string hashing/analyze+qualify)
        hash (hashing/hash @!var->hash form)
        cache-file (str cache-dir fs/*sep* hash)]
    (fs/create-dir cache-dir)
    (if (fs/exists? cache-file)
      (read-string (slurp cache-file))
      (let [result (eval form)
            var-value (cond-> result (var? result) deref)]
        (when (var? result)
          (swap! !var->hash assoc result hash))
        (if (fn? var-value)
          result
          (do (when-not (or (-> result meta :observator/no-cache)
                            (instance? clojure.lang.IDeref var-value))
                (spit cache-file (pr-str var-value)))
              var-value))))))

(comment
  (def slow-thing-1 (do (Thread/sleep 500) 42))
  (inc slow-thing-1))


(defn clear-cache!
  ([]
   (reset! !var->hash {})
   (let [cache-dir (str fs/*cwd* fs/*sep* ".cache")]
     (when (fs/exists? cache-dir)
       (fs/delete (str fs/*cwd* fs/*sep* ".cache")))))
  ([sym]
   (let [var (resolve sym)]
     (when-let [cache-file (get @!var->hash var)]
       (when (fs/exists? cache-file)
         (fs/delete cache-file)))
     (swap! !var->hash dissoc var))))


(range 1000)

(comment
  (let [r (defn foo [] :bar)
        v (cond-> r (var? r) deref)]
    (fn? v)))

(defn format-eval-output [form]
  (binding [*print-length* 10]
    (pr-str form)))

(comment
  (format-eval-output (read+eval-cached "(+ 1 2 3)")))

(defn code->panel
  "Converts the Clojure source test in `code` to a series of text or syntax panes and causes `panel` to contain them."
  [panel code]
  (.removeAll panel)
  (loop [nodes (:children (p/parse-string-all code))]
    (if-let [node (first nodes)]
      (recur (cond
               (= :list (n/tag node)) (do (.add panel
                                                (make-syntax-pane (n/string node) {:background? true}))
                                          (.add panel
                                                (make-syntax-pane (format-eval-output (read+eval-cached (n/string node)))))
                                          (rest nodes))
               (n/comment? node) (do (.add panel (make-html-pane
                                                  (md->html
                                                   (apply str (map (comp remove-leading-semicolons n/string)
                                                                   (take-while n/comment? nodes))))))
                                     (drop-while n/comment? nodes))
               :else (rest nodes)))))
  (.add panel (javax.swing.JTextPane.))
  (.validate (.getContentPane frame))
  (.repaint frame))

(defn file-event [{:keys [type path]}]
  (when-let [ns-part (and (= type :modify)
                          (second (re-find #".*/src/(.*)\.clj" (str path))))]
    (binding [*ns* (find-ns (symbol (str/replace ns-part fs/*sep* ".")))]
      (observator.core/code->panel observator.core/panel (slurp path)))))



;; And, as is the culture of our people, a commend block containing
;; pieces of code with which to pilot the system during development.
(comment
  (def watcher
    (beholder/watch #(file-event %) "src"))

  (beholder/stop watcher)
  (code->panel panel (slurp "src/observator/core.clj"))
  (code->panel panel (slurp "src/observator/lib.clj"))

  ;; Clear cache
  (clear-cache!)
  (clear-cache! 'random-cached-thing)
  )
