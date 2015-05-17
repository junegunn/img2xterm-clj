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
    (is (= 232 (rgb-to-ansi [0 0 0])))
    (is (= 234 (rgb-to-ansi [30 30 30])))
    (is (= 253 (rgb-to-ansi [220 220 220])))
    (is (= 231 (rgb-to-ansi [250 250 250])))))
