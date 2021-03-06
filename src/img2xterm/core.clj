(ns img2xterm.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli])
  (:import org.apache.commons.imaging.Imaging
           java.awt.RenderingHints
           java.awt.image.BufferedImage)
  (:gen-class))

(def ^:private cli-options
  [["-w" "--width WIDTH" "Max width (default: terminal width)"
    :parse-fn #(Integer/parseInt %)
    :validate [#(<= 0 %) "Must be a positive integer"]]
   ["-h" "--height HEIGHT" "Max height (default: terminal height)"
    :parse-fn #(Integer/parseInt %)
    :validate [#(<= 0 %) "Must be a positive integer"]]
   ["" "--24" "Enable 24-bit color output"]
   [nil "--help" "Show this message"]])

(defn- resize-image [^BufferedImage img max-width max-height]
  (let [orig-width    (.getWidth img)
        orig-height   (.getHeight img)
        orig-type     (.getType img)
        term          (jline.TerminalFactory/get)
        screen-width  (or max-width (.getWidth term))
        screen-height (* 2 (or max-height (dec (.getHeight term))))
        scale         (apply min (map #(double (/ %1 %2))
                                      [screen-width screen-height]
                                      [orig-width orig-height]))
        width         (* scale orig-width)
        height        (* scale orig-height)
        out           (BufferedImage. width height orig-type)]
    (doto (.createGraphics out)
      (.setRenderingHint RenderingHints/KEY_INTERPOLATION
                         RenderingHints/VALUE_INTERPOLATION_BICUBIC)
      (.drawImage img
                  0 0 width height
                  0 0 orig-width orig-height nil)
      (.dispose))
    out))

(def ^:private color-offsets [0 95 135 175 215 255])
(defn find-ansi-offset [c]
  (last (apply min-key first (map-indexed
                               (fn [idx val] [(Math/abs (- c val)) idx])
                               color-offsets))))
(def find-ansi-offset-cached (memoize find-ansi-offset))

(def ^:private grayscale (conj (vec (range 232 256)) 231))
(def ^:private alpha-threshold 64) ; FIXME

(defn round-error [rgb indexes]
  (let [rounded (map #(nth color-offsets %) indexes)
        pairs   (map vector rgb rounded)]
    (reduce + (map #(Math/abs (apply - %)) pairs))))

(defn rgba-to-ansi
  "Converts RGB color to 256-color ANSI code"
  [true-color rgba]
  (let [rgb (butlast rgba)
        a   (last rgba)]
    (if (< a alpha-threshold)
      :transparent
      (if true-color
        (str/join ";" rgb)
        (let [rgbi (map find-ansi-offset-cached rgb)
              avg  (/ (reduce + rgb) 3)
              step (/ 256 (count grayscale))
              idx  (Math/round (double (/ avg step)))
              avg  (int (* step idx))]
          (if (< (round-error rgb rgbi)
                 (reduce + (map #(Math/abs (- avg %)) rgb)))
            (let [[ri gi bi] rgbi]
              (+ 16 (* 36 ri) (* 6 gi) bi))
            (nth grayscale idx)))))))

(defn- as-rgba-vec
  "Interpretes TYPE_INT_ARGB color model"
  [argb]
  (map #(bit-and (bit-shift-right argb %) 0xff) [16 8 0 24]))

(def ^:private ansi-prefix (str (char 27) "["))
(def ^:private ansi-clear (str ansi-prefix "m"))
(def ^:dynamic *fg* "38;5;")
(def ^:dynamic *bg* "48;5;")
(defn- with-ansi
  "Prepends string with 256-color ANSI code"
  [string & [fg bg]]
  (str
    ansi-prefix
    (if (and fg (= fg bg) (not= :transparent fg))
      (str *bg* bg)
      (str (when fg (if (= :transparent fg) "39" (str *fg* fg)))
           (when (and fg bg) ";")
           (when bg (if (= :transparent bg) "49" (str *bg* bg)))))
    "m" string))

;; http://en.wikipedia.org/wiki/Block_Elements
(def ^:private lower-half (char 0x2584))
(def ^:private upper-half (char 0x2580))
(defn- char-for [top bot]
  (cond
    (= top bot) " "
    (= :transparent bot) upper-half
    :else lower-half))

(defn- cell->str
  [chr top bot
   prev-chr prev-top prev-bot]
  (let [fun (if (= :transparent bot) #(with-ansi %1 %3 %2) with-ansi)
        same-top (= prev-top top)
        same-bot (= prev-bot bot)]
    (if (= prev-chr chr)
      (cond
        (and same-top same-bot) chr
        same-top (fun chr bot nil)
        same-bot (fun chr nil top)
        :else (fun chr bot top))
      (fun chr bot top))))

(defn- img->str [^BufferedImage img
                 true-color]
  (let [out    (java.io.StringWriter.)
        width  (.getWidth img)
        height (.getHeight img)
        ->ansi (comp (partial rgba-to-ansi true-color)
                     as-rgba-vec
                     (fn [^BufferedImage img x y] (.getRGB img x y)))]
    (binding [*out* out
              *fg* (if true-color "38;2;" "38;5;")
              *bg* (if true-color "48;2;" "48;5;")]
      (loop [ys (range height)]
        (when-let [y (first ys)]
          (loop [xs (range width)
                 ch nil
                 ct nil
                 cb nil]
            (when-let [x (first xs)]
              (let [new-ct (->ansi img x y)
                    new-cb (if (< y (dec height))
                             (->ansi img x (inc y)) :transparent)
                    new-ch (char-for new-ct new-cb)]
                (print (cell->str new-ch new-ct new-cb ch ct cb))
                (recur (rest xs) new-ch new-ct new-cb))))
          (println ansi-clear)
          (recur (rest (rest ys)))))
      (str out))))

(defn ->str
  "Returns the string representation of the image"
  [filename max-width max-height]
  (img->str
    (resize-image
      (Imaging/getBufferedImage (io/as-file filename))
      max-width max-height)))

;; http://commons.apache.org/proper/commons-imaging/apidocs/index.html
(defn- as-buffered-image [^java.io.File file]
  (try (Imaging/getBufferedImage file)
       (catch Exception e
         (printf "** %s: %s\n" (.getName file) (str e)))))

(defn- print-exit [code & lines]
  (doseq [line lines] (println line))
  (System/exit code))

(defn -main
  [& args]
  (let [{:keys [options arguments summary errors]}
        (cli/parse-opts args cli-options)
        {:keys [width height]} options]
    (cond (contains? options :help)
          (print-exit 0
                      "Usage: img2xterm [OPTIONS] IMAGE..."
                      "Options:" summary)
          (seq errors)
          (apply print-exit 1 errors))
    (let [images (->> arguments
                      (map io/as-file)
                      (filter #(.exists ^java.io.File %))
                      (map as-buffered-image)
                      (filter some?))]
      (if (empty? images) (print-exit 1 "No valid image files given"))
      (doseq [img images]
        (print (img->str
                 (resize-image img width height)
                 (:24 options))))
      (flush))))
