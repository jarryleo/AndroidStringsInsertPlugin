package cn.jarryleo.demo

interface OnStringsInsertListener {
    fun onInsert(stringName: String, stringsInfoList: Map<String, String>)
}