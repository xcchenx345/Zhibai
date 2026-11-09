// Verbatim port of the official MNN callback interface — the prebuilt
// libmnnllmapp.so looks this class up by name and calls
// onProgress(Ljava/lang/String;)Z. Do not rename or change the signature.
package com.alibaba.mnnllm.android.llm

interface GenerateProgressListener {
    fun onProgress(progress: String?): Boolean
}
