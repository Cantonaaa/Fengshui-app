package com.fengshui.app

/** 测试用 bagua_data.json 内容（与 assets 一致）。 */
object TestData {
    const val BAGUA_JSON = """
    {
      "xing": {
        "生气": "吉", "延年": "吉", "天乙": "吉", "伏位": "吉",
        "绝命": "凶", "五鬼": "凶", "六煞": "凶", "祸害": "凶"
      },
      "youNianOrder": {
        "坎": "五鬼天乙生气延年绝命祸害六煞伏位",
        "离": "六煞五鬼绝命延年祸害生气天乙伏位",
        "震": "延年生气祸害绝命五鬼天乙六煞伏位",
        "巽": "天乙五鬼六煞祸害生气绝命延年伏位",
        "乾": "六煞天乙五鬼祸害绝命延年生气伏位",
        "坤": "天乙延年绝命生气祸害五鬼六煞伏位",
        "兑": "生气祸害绝命延年六煞五鬼天乙伏位",
        "艮": "六煞绝命祸害生气延年五鬼天乙伏位"
      },
      "dongXiSiWei": {
        "东四位": ["震", "巽", "坎", "离"],
        "西四位": ["乾", "坤", "艮", "兑"]
      }
    }
    """
}

class MingGuaTest {

    @org.junit.Test
    fun trigram_male_female() {
        // 男：1990 → 坎；女：1990 → 艮（余5）
        assert(MingGua.trigram(1990, "男") == "坎")
        assert(MingGua.trigram(1990, "女") == "艮")
        // 男：1991 → 余0按9 → 离；女：1991 → (91-4)=87%9=6 → 乾
        assert(MingGua.trigram(1991, "男") == "离")
        assert(MingGua.trigram(1991, "女") == "乾")
        // 男余5 → 坤；女：1988 → (88-4)=84%9=3 → 震
        assert(MingGua.trigram(1995, "男") == "坤")
        assert(MingGua.trigram(1988, "女") == "震")
        // 2000（后两位00）男 → (100)%9=1 → 坎
        assert(MingGua.trigram(2000, "男") == "坎")
    }

    @org.junit.Test
    fun group() {
        assert(MingGua.group("震") == "东四命")
        assert(MingGua.group("巽") == "东四命")
        assert(MingGua.group("坎") == "东四命")
        assert(MingGua.group("离") == "东四命")
        assert(MingGua.group("乾") == "西四命")
        assert(MingGua.group("坤") == "西四命")
        assert(MingGua.group("艮") == "西四命")
        assert(MingGua.group("兑") == "西四命")
    }

    @org.junit.Test
    fun guaDirections_standard() {
        // 坎命：吉{北,东,东南,南} 凶{西南,东北,西,西北}
        val kan = MingGua.compute(1990, "男", TestData.BAGUA_JSON)
        assert(kan.trigram == "坎")
        assert(kan.goodSectors.toSet() == setOf("北", "东", "东南", "南")) {
            "坎命吉方错误: ${kan.goodSectors}"
        }
        assert(kan.badSectors.toSet() == setOf("西南", "东北", "西", "西北"))
        // 离命：吉{南,东,北,东南} 凶{西南,西,西北,东北}
        val li = MingGua.compute(1991, "男", TestData.BAGUA_JSON)
        assert(li.trigram == "离")
        assert(li.goodSectors.toSet() == setOf("南", "东", "北", "东南"))
        // 震命（1988 女）：吉{东,西南,东南,东北} 凶{南,西,西北,北}
        val zhen = MingGua.compute(1988, "女", TestData.BAGUA_JSON)
        assert(zhen.trigram == "震")
        assert(zhen.goodSectors.toSet() == setOf("东", "西南", "东南", "东北"))
        assert(zhen.badSectors.toSet() == setOf("南", "西", "西北", "北"))
    }
}
