package cn.jarryleo.insert_strings

interface OnStringsInsertListener {
    fun onInsert(stringName: String, stringsInfoList: Map<String, String>)
}