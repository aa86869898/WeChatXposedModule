package com.leshao.v3

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayList

open class ShadowLabelStore {

    class ShadowLabel {
        @JvmField
        var labelId: Int = 0

        @JvmField
        var labelName: String = ""

        @JvmField
        var createTime: Long = 0

        constructor() {}

        constructor(id: Int, name: String, time: Long) {
            this.labelId = id
            this.labelName = name
            this.createTime = time
        }

        fun toJson(): JSONObject {
            val o = JSONObject()
            try {
                o.put("id", labelId)
                o.put("name", labelName)
                o.put("time", createTime)
            } catch (ignored: Throwable) {}
            return o
        }

        companion object {
            @JvmStatic
            fun fromJson(o: JSONObject): ShadowLabel {
                val l = ShadowLabel()
                l.labelId = o.optInt("id", -1)
                l.labelName = o.optString("name", "")
                l.createTime = o.optLong("time", 0)
                return l
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "leshao_shadow_labels"
        private const val KEY_LABELS = "labels_json"
        private const val KEY_LABEL_ORDER = "label_order"

        @Volatile
        private var sPrefs: SharedPreferences? = null

        @JvmStatic
        fun init(ctx: Context?) {
            if (sPrefs == null && ctx != null) {
                sPrefs = UnifiedPrefs.get(ctx, PREFS_NAME)
            }
        }

        @JvmStatic
        fun getAll(): MutableList<ShadowLabel> {
            val list = ArrayList<ShadowLabel>()
            try {
                val prefs = sPrefs ?: return list
                val json = prefs.getString(KEY_LABELS, "")
                if (json.isNullOrEmpty()) return list
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    list.add(ShadowLabel.fromJson(arr.getJSONObject(i)))
                }
            } catch (e: Throwable) {
                LogWriter.log("ShadowStore", "getAll: ${e.message}")
            }
            return list
        }

        @JvmStatic
        fun add(labelId: Int, name: String) {
            try {
                val prefs = sPrefs ?: return
                val list = getAll()
                for (l in list) {
                    if (l.labelName == name) return
                }
                list.add(ShadowLabel(labelId, name, System.currentTimeMillis()))
                save(list)
                LogWriter.log("ShadowStore", "add: id=$labelId name=$name")
            } catch (e: Throwable) {
                LogWriter.log("ShadowStore", "add error: ${e.message}")
            }
        }

        @JvmStatic
        fun rename(labelId: Int, newName: String) {
            try {
                val prefs = sPrefs ?: return
                val list = getAll()
                for (l in list) {
                    if (l.labelId == labelId) {
                        l.labelName = newName
                        save(list)
                        return
                    }
                }
            } catch (e: Throwable) {
                LogWriter.log("ShadowStore", "rename error: ${e.message}")
            }
        }

        @JvmStatic
        fun remove(labelId: Int) {
            try {
                val prefs = sPrefs ?: return
                val list = getAll()
                val it = list.iterator()
                while (it.hasNext()) {
                    if (it.next().labelId == labelId) {
                        it.remove()
                        break
                    }
                }
                save(list)
                LogWriter.log("ShadowStore", "remove: id=$labelId")
            } catch (e: Throwable) {
                LogWriter.log("ShadowStore", "remove error: ${e.message}")
            }
        }

        @JvmStatic
        fun updateId(oldId: Int, newId: Int, name: String) {
            try {
                val prefs = sPrefs ?: return
                val list = getAll()
                for (l in list) {
                    if (l.labelId == oldId && l.labelName == name) {
                        l.labelId = newId
                        save(list)
                        LogWriter.log("ShadowStore", "updateId: $oldId -> $newId")
                        return
                    }
                }
            } catch (e: Throwable) {
                LogWriter.log("ShadowStore", "updateId error: ${e.message}")
            }
        }

        @JvmStatic
        fun getLabelOrder(): List<Int> {
            val r = ArrayList<Int>()
            try {
                val prefs = sPrefs ?: return r
                val json = prefs.getString(KEY_LABEL_ORDER, "")
                if (json.isNullOrEmpty()) return r
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) r.add(arr.getInt(i))
            } catch (e: Throwable) {
                LogWriter.log("ShadowStore", "getOrder: ${e.message}")
            }
            return r
        }

        @JvmStatic
        fun saveLabelOrder(order: List<Int>) {
            try {
                val prefs = sPrefs ?: return
                val arr = JSONArray()
                for (id in order) arr.put(id)
                prefs.edit().putString(KEY_LABEL_ORDER, arr.toString()).apply()
            } catch (e: Throwable) {
                LogWriter.log("ShadowStore", "saveOrder: ${e.message}")
            }
        }

        @JvmStatic
        fun save(list: List<ShadowLabel>) {
            try {
                val prefs = sPrefs ?: return
                val arr = JSONArray()
                for (l in list) arr.put(l.toJson())
                prefs.edit().putString(KEY_LABELS, arr.toString()).apply()
            } catch (e: Throwable) {
                LogWriter.log("ShadowStore", "save error: ${e.message}")
            }
        }
    }
}