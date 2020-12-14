package servlet;

public class FileType {
    static final int SYSTEM_FILE = 1;
    static final int USER_FILE = 0;
}
class Mode{
    static final int READ_ONLY = 0;
    static final int WRITE_FIRST = 1;
    static final int WRITE_APPEND = 2;
    static final int READ_WRITE = 3;
}
class Permission{
    static final int READ_ONLY = 1;
    static final int WRITE_ONLY = 2;
    static final int EXECUTABLE = 4;
    static final int READ_WRITE = 3;
    static final int READ_EXEC = 5;
    static final int WRITE_EXEC = 6;
    static final int RW_EXEC = 7;
}
class Tag{
    static final int FILE_TYPE = 0;
    static final int DIRECTORY_TYPE = 1;
}
class Error{
    static final int DUPLICATION = 0;
    static final int PERMISSION_DENIED = 1;
    static final int PATH_NOT_FOUND = 2;
    static final int DISK_OVERFLOW = 3;
    static final int USING_BY_OTHERS = 4;
}