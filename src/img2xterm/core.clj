(ns img2xterm.core
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.tools.cli :as cli])
  (:import org.apache.commons.imaging.Imaging
           java.awt.RenderingHints
           java.awt.image.BufferedImage)
  (:gen-class))

(def cli-options
  [["-w" "--width WIDTH" "Max width (default: terminal width)"
    :parse-fn #(Integer/parseInt %)
    :validate [#(<= 0 %) "Must be a positive integer"]]
   ["-h" "--height HEIGHT" "Max height (default: terminal height)"
    :parse-fn #(Integer/parseInt %)
    :validate [#(<= 0 %) "Must be a positive integer"]]
   [nil "--help" "Show this message"]])

(defn- resize-image [img max-width max-height]
  (let [orig-width (.getWidth img)
        orig-height (.getHeight img)
        orig-type (.getType img)
        term (jline.TerminalFactory/get)
        screen-width (or max-width (.getWidth term))
        screen-height (* 2 (or max-height (.getHeight term)))
        scale (apply min (map #(double (/ %1 %2))
                              [screen-width screen-height]
                              [orig-width orig-height]))
        width (* scale orig-width)
        height (* scale orig-height)
        out (BufferedImage. width height orig-type)]
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

(def ^:private grayscale (conj (vec (range 232 256)) 231))
(defn rgba-to-ansi
  "Converts RGB color to 256-color ANSI code"
  [rgba]
  (let [rgb (butlast rgba)
        a (last rgba)
        [r g b] (map find-ansi-offset rgb)]
    (if (= a 0)
      :transparent
      (if (= r g b)
        (let [avg (/ (reduce + rgb) 3)
              idx (/ avg (/ 256 (count grayscale)))]
          (nth grayscale idx))
        (+ 16 (* 36 r) (* 6 g) b)))))

(defn- as-rgba-vec
  "Interpretes TYPE_INT_ARGB color model"
  [argb]
  (map #(bit-and (bit-shift-right argb %) 0xff) [16 8 0 24]))

(defn- with-ansi
  "Prepends string with 256-color ANSI code"
  [string & [fg bg]]
  (str (char 27) "["
       (s/join
         ";"
         (filter some?
                 [(when fg (if (= :transparent fg) "39" (str "38;5;" fg)))
                  (when bg (if (= :transparent bg) "49" (str "48;5;" bg)))]))
       "m" string))

;; http://en.wikipedia.org/wiki/Block_Elements
(def ^:private lower-half (char 0x2584))
(def ^:private upper-half (char 0x2580))

(defn- print-cell! [top bot prev-top prev-bot]
  "Prints cell"
  (let [chr (cond
             (= :transparent top bot) " "
             (= :transparent bot) upper-half
             :else lower-half)
        ; since the calls assumes lower-half, we need to swap the order
        fun (if (= :transparent bot) #(with-ansi %1 %3 %2) with-ansi)]
    (print
      (cond
        (and (= prev-top top) (= prev-bot bot)) chr
        (= prev-top top) (fun chr bot nil)
        (= prev-bot bot) (fun chr nil top)
        :else (fun chr bot top)))))

(defn- print-image! [img]
  (let [width (.getWidth img)
        height (.getHeight img)
        pixel-to-ansi (comp rgba-to-ansi as-rgba-vec #(.getRGB %1 %2 %3))]
    (loop [ys (range height)]
      (when-let [y (first ys)]
        (loop [xs (range width)
               ct nil
               cb nil]
          (when-let [x (first xs)]
            (let [new-ct (pixel-to-ansi img x y)
                  new-cb (if (< y (dec height))
                           (pixel-to-ansi img x (inc y)) :transparent)]
              (print-cell! new-ct new-cb ct cb)
              (recur (rest xs) new-ct new-cb))))
        (println (with-ansi ""))
        (recur (rest (rest ys)))))))

;; http://commons.apache.org/proper/commons-imaging/apidocs/index.html
(defn- as-buffered-image [^java.io.File file]
  (try
    (Imaging/getBufferedImage file)
    (catch Exception e
      (printf "** %s: %s\n" (.getName file) (str e))
      nil)))

(defn -main
  [& args]
  (let [{:keys [options arguments summary errors]} (cli/parse-opts args cli-options)
        help (contains? options :help)]
    (cond help (do (println "Usage: java -jar img2xterm-*.jar [OPTIONS] IMAGE...")
                   (println "Options:")
                   (println summary)
                   (System/exit 0))
          (seq errors) (do (doseq [error errors] (println error))
                           (System/exit 1)))
    (let [images (->> arguments
                      (map io/as-file)
                      (filter #(.exists %))
                      (map as-buffered-image)
                      (filter some?))]
      (if (empty? images) (do (println "No valid image files given")
                              (System/exit 1)))
      (doseq [img images]
        (print-image!
          (resize-image img
                        (:width options)
                        (:height options)))))))
