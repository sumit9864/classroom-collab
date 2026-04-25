package com.classroom.model;

import java.io.Serializable;

/**
 * Serializable payload for file-sharing messages.
 *
 * Used by three MessageTypes:
 *   FILE_SHARE_START    – metadata only (chunkData == null, chunkIndex == -1)
 *   FILE_CHUNK          – one chunk of file data (chunkIndex >= 0)
 *   FILE_SHARE_COMPLETE – transfer-done signal (chunkData == null, chunkIndex == -2)
 *
 * The static factory methods enforce which fields are meaningful for each type.
 */
public class FileShareData implements Serializable {
    private static final long serialVersionUID = 5L;

    /** Chunk size used by the sender. Students use this to calculate progress. */
    public static final int CHUNK_SIZE = 256 * 1024; // 256 KB

    // ── Fields ────────────────────────────────────────────────────────────────
    private final String transferId;   // UUID string – unique per file transfer
    private final String fileName;     // original file name including extension
    private final long   totalBytes;   // full file size in bytes
    private final int    totalChunks;  // total number of chunks (0 for COMPLETE)
    private final int    chunkIndex;   // -1 = START meta, -2 = COMPLETE, >= 0 = chunk index
    private final byte[] chunkData;    // actual bytes for this chunk (null for START/COMPLETE)

    private FileShareData(String transferId, String fileName, long totalBytes,
                          int totalChunks, int chunkIndex, byte[] chunkData) {
        this.transferId  = transferId;
        this.fileName    = fileName;
        this.totalBytes  = totalBytes;
        this.totalChunks = totalChunks;
        this.chunkIndex  = chunkIndex;
        this.chunkData   = chunkData;
    }

    // ── Static factories ──────────────────────────────────────────────────────

    /** Creates a FILE_SHARE_START payload (metadata, no data bytes). */
    public static FileShareData start(String transferId, String fileName,
                                      long totalBytes, int totalChunks) {
        if (transferId == null || fileName == null)
            throw new IllegalArgumentException("transferId and fileName must not be null");
        return new FileShareData(transferId, fileName, totalBytes, totalChunks, -1, null);
    }

    /** Creates a FILE_CHUNK payload. chunkData must not be null or empty. */
    public static FileShareData chunk(String transferId, String fileName, long totalBytes,
                                      int totalChunks, int chunkIndex, byte[] chunkData) {
        if (chunkData == null || chunkData.length == 0)
            throw new IllegalArgumentException("chunkData must not be null or empty");
        if (chunkIndex < 0)
            throw new IllegalArgumentException("chunkIndex must be >= 0");
        return new FileShareData(transferId, fileName, totalBytes, totalChunks, chunkIndex, chunkData);
    }

    /** Creates a FILE_SHARE_COMPLETE payload (no data bytes). */
    public static FileShareData complete(String transferId, String fileName, long totalBytes) {
        return new FileShareData(transferId, fileName, totalBytes, 0, -2, null);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────
    public String getTransferId()  { return transferId; }
    public String getFileName()    { return fileName; }
    public long   getTotalBytes()  { return totalBytes; }
    public int    getTotalChunks() { return totalChunks; }
    public int    getChunkIndex()  { return chunkIndex; }
    public byte[] getChunkData()   { return chunkData; }

    /** True when this payload is the initial metadata message. */
    public boolean isStart()    { return chunkIndex == -1; }
    /** True when this payload signals transfer completion. */
    public boolean isComplete() { return chunkIndex == -2; }
    /** True when this payload carries file data. */
    public boolean isChunk()    { return chunkIndex >= 0; }
}
