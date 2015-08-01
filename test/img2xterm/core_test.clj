(ns img2xterm.core-test
  (:require [clojure.test :refer :all]
            [img2xterm.core :refer :all]))

(deftest ansi-test
  (testing "find-ansi-offset"
    (is (= 0 (find-ansi-offset 0)))
    (is (= 0 (find-ansi-offset 40)))
    (is (= 1 (find-ansi-offset 90)))
    (is (= 5 (find-ansi-offset 250))))

  (testing "rgb-to-ansi grayscale"
    (is (= 232 (rgba-to-ansi false [0 0 0 255])))
    (is (= 234 (rgba-to-ansi false [30 30 30 255])))
    (is (= 253 (rgba-to-ansi false [220 220 220 255])))
    (is (= 231 (rgba-to-ansi false [250 250 250 255])))))
