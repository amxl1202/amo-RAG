package com.ragkb.storage;

import java.io.InputStream;

/**
 * 文件存储抽象。第一版只有本地磁盘实现;将来换 MinIO/S3 时新增实现类,
 * 业务代码不感知存储介质 —— 这里的依赖倒置是为了让"文件在哪"成为一个可替换的细节。
 */
public interface FileStorage {

    /**
     * 保存文件内容,返回存储系统内部的相对路径。
     *
     * @param digestHex 内容 SHA-256(hex 小写),存储层用它做内容寻址,同名即同内容
     * @param extension 扩展名(不含点),如 "pdf"
     * @param content   文件字节流,由调用方负责关闭
     */
    StoredFile store(String digestHex, String extension, InputStream content);

    /** 删除一个已存储的文件;文件不存在时静默返回(幂等)。 */
    void delete(String storagePath);

    /** 取回文件字节流,调用方负责关闭。不存在时抛 IOException。 */
    InputStream open(String storagePath);

    /** store 的返回值:相对路径 + 实际落盘字节数 */
    record StoredFile(String storagePath, long size) {
    }
}
