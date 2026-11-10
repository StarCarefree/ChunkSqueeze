package cn.star.chunksqueeze.compat;

public final class NbtAccounterException extends RuntimeException {
    public NbtAccounterException(String message) {
        super(message);
    }

    private NbtAccounterException(String message, Throwable cause) {
        super(message, cause);
    }

    // 只转换原生解析器的深度异常，损坏数据和其他运行时异常保持原样。
    public static RuntimeException normalize(RuntimeException exception) {
        if (exception.getClass() == RuntimeException.class
                && "Tried to read NBT tag with too high complexity, depth > 512".equals(exception.getMessage())) {
            return new NbtAccounterException(exception.getMessage(), exception);
        }
        return exception;
    }
}
