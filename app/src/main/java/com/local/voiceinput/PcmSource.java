package com.local.voiceinput;

/** 统一的录音源：系统 AudioRecord，或 root 中继。 */
public interface PcmSource {

    /** 读最多 maxBytes 字节的 16k 单声道 PCM；返回实际字节数，0 表示暂时没有数据。 */
    int read(byte[] out, int maxBytes);

    void close();
}
