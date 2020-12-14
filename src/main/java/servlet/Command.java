package servlet;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.crypto.Data;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

@WebServlet(name = "cmd", urlPatterns = {"/cmd"})
public class Command extends HttpServlet {
    private Disk disk;
    private User current_user;
    private DirectoryItem current_dir;
    private HashMap<Integer, String> buffer;
    private int id = 0;
    public PrintWriter out;
    @Override
    public void init() {
        disk = new Disk();
        try {
            //创建一个用户
            createUser("li554","123456",Permission.RW_EXEC);
            //登录用户
            login("li554","123456");
            //先创建一个文件夹
            mkdir("test1","/li554/");
            mkdir("test2","/li554/");
            mkdir("test3","/li554/");
            //试试通过相对路径创建一个目录
            mkdir("test11","test1");
            //试试通过相对路径进入文件夹test11;
            cd("test1/test11");
            //试试在test11文件夹下创建一个文件
            create("a.txt","",Permission.RW_EXEC);
            //试试以覆盖写的方式打开一个文件
            int uid = open("a.txt","",Mode.WRITE_FIRST);
            //试试向文件a.txt写入一段字符串
            write(uid,"hello world");
            //试试关闭一个文件
            close(uid);
            //重新以读的方式打开a.txt,并读出数据
            int uid2 = open("a.txt","",Mode.READ_ONLY);
            System.out.println(read(uid2));
            close(uid2);
            //再次以添加写的方式打开a.txt
            int uid3 = open("a.txt","",Mode.WRITE_APPEND);
            write(uid3,"you are very beautiful");
            close(uid3);
            //再次读一下a.txt文件
            int uid4 = open("a.txt","",Mode.READ_ONLY);
            System.out.println(read(uid4));
            close(uid4);
        }catch (IOException e){
            e.printStackTrace();
        }
    }

    private void error(int n){
//        switch (n){
//            case Error.DUPLICATION:
//                out.println("重名");
//                break;
//            case Error.DISK_OVERFLOW:
//                out.println("磁盘已满，分配失败");
//                break;
//            case Error.PATH_NOT_FOUND:
//                out.println("未找到指定路径");
//                break;
//            case Error.PERMISSION_DENIED:
//                out.println("权限不足");
//                break;
//            case Error.USING_BY_OTHERS:
//                out.println("文件被其他进程占用");
//        }
    }

    private void createUser(String uname, String psw, int permission) {
        User user = new User(uname, psw, permission);
        disk.sroot.dirs.add(user.uroot);
        disk.usertable.add(user);
    }

    private void login(String username, String psw) throws IOException {
        for (User user : disk.usertable) {
            if (user.name.equals(username) && user.psw.equals(psw)) {
                current_user = user;
                current_dir = user.uroot;
                return;
            }
        }
    }

    private void delete(DirectoryItem file, DirectoryItem parent) {
        if (check_permission(file.permission, -1)) {
            recoverDist(file.fcb);
            parent.dirs.remove(parent);
            parent.lastModifyTime = new Date().getTime();
        } else {
            //out.println("您没有删除当前文件的权限");
            error(Error.PERMISSION_DENIED);
        }
    }

    private DirectoryItem getParent(String path) {
//        首先判断路径的合法性
//        String pattern = "([a-zA-Z]:)?(\\\\[a-zA-Z0-9_.-]+)+\\\\?";
//        if (!Pattern.matches(pattern,path)){
//            return null;
//        }
        if (path!=null&&path.length() > 0) {
            char ch = path.charAt(0);
            //分割路径，得到各级目录名
            String[] temp = path.split("/");
            //删除空字符串，最终路径结果保留在dirs数组中
            List<String> dirs = new ArrayList<>();
            for (String dir : temp) {
                if (!dir.equals("")) {
                    dirs.add(dir);
                }
            }
            //根据第一个字符是否为/判断path为绝对路径还是相对路径
            if (ch == '/') {
                return recurSearch(disk.sroot, dirs, 0);
            } else {
                return recurSearch(current_dir, dirs, 0);
            }
        } else {
            return current_dir;
        }
    }

    private DirectoryItem recurSearch(DirectoryItem dir, List<String> dirname, int level) {
        if (level >= dirname.size()) {
            return dir;
        }
        for (DirectoryItem item : dir.dirs) {
            if (item.name.equals(dirname.get(level)) && item.dirs != null) {
                return recurSearch(item, dirname, level + 1);
            }
        }
        return null;
    }

    private void rmdir(DirectoryItem dir) {
        for (DirectoryItem sitem : dir.dirs) {
            if (sitem.tag == Tag.FILE_TYPE) {
                delete(sitem, dir);
            } else {
                rmdir(sitem);
            }
        }
    }

    synchronized private boolean requestDist(LinkedList<DiskBlockNode> list, int maxLength) {
        boolean flag = false;   // 标记是否分配成功
        int num = maxLength % Disk.MAXDISKBLOCK == 0 ? maxLength / Disk.MAXDISKBLOCK : maxLength / Disk.MAXDISKBLOCK + 1;      //算出需要几个磁盘块
        //判断当前空闲盘块数目是否足够
        if (num <= disk.diskBlockList.size()) {
            int i = 0;
            while (i < num) {
                DiskBlockNode node = disk.diskBlockList.remove();
                list.add(node);
                i++;
            }
            flag = true;
        }
        return flag;
    }

    synchronized private void recoverDist(FCB fcb) {
        while (!fcb.flist.isEmpty()) {
            DiskBlockNode node = fcb.flist.remove();
            disk.diskBlockList.add(node);
        }
    }

    private boolean check_permission(int permission, int mode) {
        switch (current_user.permission) {
            case Permission.READ_ONLY:
                if (mode != Mode.READ_ONLY) {
                    //out.println("该用户为只读权限，不能对文件进行修改");
                    error(Error.PERMISSION_DENIED);
                    return false;
                }
                break;
            case Permission.READ_WRITE:
            case Permission.RW_EXEC:
                if (mode == Mode.READ_ONLY) {
                    switch (permission) {
                        case Permission.READ_WRITE:
                            break;
                        case Permission.READ_EXEC:
                            break;
                        case Permission.READ_ONLY:
                            break;
                        case Permission.RW_EXEC:
                            break;
                        default:
                            //out.println("文件对用户为只读权限");
                            error(Error.PERMISSION_DENIED);
                            return false;
                    }
                } else if (mode == Mode.WRITE_APPEND || mode == Mode.WRITE_FIRST) {
                    switch (permission) {
                        case Permission.READ_WRITE:
                            break;
                        case Permission.WRITE_EXEC:
                            break;
                        case Permission.WRITE_ONLY:
                            break;
                        case Permission.RW_EXEC:
                            break;
                        default:
                            //out.println("文件对用户为只写权限");
                            error(Error.PERMISSION_DENIED);
                            return false;
                    }
                } else if (mode == Mode.READ_WRITE) {
                    switch (permission) {
                        case Permission.READ_WRITE:
                            break;
                        case Permission.RW_EXEC:
                            break;
                        default:
                            //out.println("该文件对用户并不具有可读加可写的权限");
                            error(Error.PERMISSION_DENIED);
                            return false;
                    }
                }
                break;
        }
        return true;
    }

    private void cd(String path) {
        current_dir = getParent(path);
    }

    private void dir() {
        for (DirectoryItem item : current_dir.dirs) {
            System.out.println(item.name);
            //out.println(item.name);
        }
    }

    private void mkdir(String name, String path) {
        //首先检查是否重名
        DirectoryItem result = getParent(path);
        if (result == null) {
            //out.println("未找到指定路径");
            error(Error.PATH_NOT_FOUND);
            return;
        }
        //在获得的目录中查找是否已经存在该名字的文件，找到则提示重名错误
        for (DirectoryItem item : result.dirs) {
            if (item.name.equals(name)) {
                //发生重名
                //out.println("创建失败，存在重名文件");
                error(Error.DUPLICATION);
                return;
            }
        }
        DirectoryItem item = new DirectoryItem(name, Permission.RW_EXEC, Tag.DIRECTORY_TYPE);
        item.parent = result;
        result.dirs.add(item);
        result.creatTime = new Date().getTime();
        result.lastModifyTime = result.creatTime;
    }

    private void rmdir(String name, String path) throws IOException {
        DirectoryItem result = getParent(path);
        if (result == null) {
            //out.println("未找到指定路径");
            error(Error.PATH_NOT_FOUND);
            return;
        }
        //在获得的目录中查找对应的目录
        for (DirectoryItem item : result.dirs) {
            if (item.name.equals(name)) {
                rmdir(item);
                result.dirs.remove(item);
                result.lastModifyTime = new Date().getTime();
                return;
            }
        }
    }

    private void create(String name, String path, int permission) throws IOException {
        //首先检查是否重名
        DirectoryItem result = getParent(path);
        if (result == null) {
            //out.println("未找到指定路径");
            error(Error.PATH_NOT_FOUND);
            return;
        }
        //在获得的目录中查找是否已经存在该名字的文件，找到则提示重名错误
        for (DirectoryItem item : result.dirs) {
            if (item.name.equals(name)) {
                //发生重名
                //out.println("创建失败，存在重名文件");
                error(Error.DUPLICATION);
                return;
            }
        }
        LinkedList<DiskBlockNode> list = new LinkedList<>();
        //不重名，则开始分配空间
        if (requestDist(list, 0)) {        //如果分配空间成功
            FCB fcb = new FCB();
            fcb.flist = list;
            fcb.creatTime = new Date().getTime();
            fcb.lastModifyTime = fcb.creatTime;
            fcb.size = 0;
            fcb.type = FileType.USER_FILE;
            fcb.usecount = 0;
            fcb.permission = permission;
            DirectoryItem item = new DirectoryItem(name, permission, Tag.FILE_TYPE);
            item.fcb = fcb;
            item.parent = result;
            result.dirs.add(item);
            result.lastModifyTime = fcb.creatTime;
        } else {
            //out.println("内存已满，分配空间失败");
            error(Error.DISK_OVERFLOW);
        }
    }

    private void delete(String name, String path) throws IOException {
        //首先直接在系统打开文件表中查找该文件，获取打开计数器的值
        for (SystemOpenFile file : disk.softable) {
            if (file.filename.equals(name) && file.opencount > 0) {
                //out.println("存在进程正在使用该文件,无法删除");
                error(Error.USING_BY_OTHERS);
                return;
            }
        }
        DirectoryItem result = getParent(path);
        if (result == null) {
            //out.println("未找到指定路径");
            error(Error.PATH_NOT_FOUND);
            return;
        }
        for (DirectoryItem item : result.dirs) {
            if (item.name.equals(name)) {
                //没有被占用的话，判断一下用户是否有删除文件的权限
                delete(item, result);
                break;
            }
        }
    }

    private int open(String name, String path, int mode) throws IOException {     //传入的是一个引用对象
        DirectoryItem result = getParent(path);
        if (result == null) {
            //out.println("未找到指定路径");
            error(Error.PATH_NOT_FOUND);
            return -1;
        }
        //找到所在目录后，从目录项中读取该文件的信息，首先判断是否有打开权限
        for (DirectoryItem item : result.dirs) {
            if (item.name.equals(name)) {
                if (check_permission(item.permission, mode)) {
                    //权限足够，接着检查是否当前进程已经打开了该文件
                    for (UserOpenFile uof : disk.uoftable) {
                        if (uof.filename.equals(name)) {
                            //表示该文件已经被打开，那么需要判断一下打开模式是否是重写
                            if (mode == Mode.WRITE_FIRST) {
                                //如果是重写，那么先回收分配的磁盘块，然后重新根据大小赋予新的磁盘块
                                recoverDist(item.fcb);
                                uof.wpoint = 0;
                                uof.mode = Mode.WRITE_FIRST;
                            }
                            return uof.uid;
                        }
                    }
                    //没有打开，那么将该文件的相关信息填入到用户进程打开文件表和系统进程打开文件表，同时将文件的使用计数增加1
                    item.fcb.usecount++;
                    UserOpenFile userOpenFile = new UserOpenFile();
                    userOpenFile.filename = name;
                    userOpenFile.mode = mode;
                    if (mode == Mode.WRITE_FIRST) {
                        //如果是重写，那么先回收分配的磁盘块，然后重新根据大小赋予新的磁盘块
                        recoverDist(item.fcb);
                        userOpenFile.wpoint = 0;
                    } else {
                        userOpenFile.wpoint = item.fcb.size;
                    }
                    userOpenFile.rpoint = 0;
                    userOpenFile.uid = disk.uoftable.size();
                    userOpenFile.sid = disk.softable.size();
                    disk.uoftable.add(userOpenFile);

                    SystemOpenFile systemOpenFile = new SystemOpenFile();
                    systemOpenFile.sid = disk.softable.size();
                    systemOpenFile.filename = name;
                    //打开计数器增加 1
                    systemOpenFile.opencount += 1;
                    //将该文件的目录项指针存在系统打开文件表中
                    systemOpenFile.fitem = item;
                    disk.softable.add(systemOpenFile);
                    //最终应该返回一个文件描述符，也就是该文件在用户进程打开文件表中的uid
                    return userOpenFile.uid;
                } else {
                    //out.println("您没有打开该文件的权限");
                    error(Error.PERMISSION_DENIED);
                }
                break;
            }
        }
        return -1;
    }

    private void close(int uid) {
        //关闭文件,需要提供一个文件描述符,系统在用户进程中的打开文件表找到对应文件，然后删除该文件项
        //并且根据获得的sid查找系统打开文件表，如果系统打开文件表中对应表项的打开计数器的值>1那么-1，否则，删除表项
        try {
            UserOpenFile uitem = disk.uoftable.get(uid);
            SystemOpenFile sitem = disk.softable.get(uitem.sid);
            //将文件的使用计数-1
            sitem.fitem.fcb.usecount--;
            //每关闭一个文件，都需要对所有的文件重新编号
            for (UserOpenFile item : disk.uoftable) {
                if (item.uid > uid) {
                    item.uid--;
                }
            }
            disk.uoftable.remove(uid);
            if (sitem.opencount > 1) {
                sitem.opencount--;
            } else {
                for (UserOpenFile item : disk.uoftable) {
                    if (item.sid > uitem.sid) {
                        item.sid--;
                    }
                }
                for (SystemOpenFile item : disk.softable) {
                    if (item.sid > uitem.sid) {
                        item.sid--;
                    }
                }
                disk.softable.remove(sitem.sid);
            }
        } catch (IndexOutOfBoundsException e) {
            System.out.println("文件已经关闭，无需再次关闭");
        }
    }

    private String read(int uid) {
        UserOpenFile uitem = disk.uoftable.get(uid);
        if (uitem.mode == Mode.WRITE_FIRST || uitem.mode == Mode.WRITE_APPEND) {
            //out.println("当前为只写模式");
            error(Error.PERMISSION_DENIED);
            return null;
        } else {
            SystemOpenFile sitem = disk.softable.get(uitem.sid);
            StringBuilder content = new StringBuilder();
            for (DiskBlockNode node : sitem.fitem.fcb.flist) {
                content.append(new String(node.content));
            }
            return content.toString();
        }
    }

    private void write(int uid, String content) {
        UserOpenFile uitem = disk.uoftable.get(uid);
        if (uitem.mode == Mode.READ_ONLY) {
            //out.println("当前为只读模式模式");
            error(Error.PERMISSION_DENIED);
            return;
        } else {
            SystemOpenFile sitem = disk.softable.get(uitem.sid);
            //现在已经获取到了该文件，通过前端传来的的文本值，我们将该值写入文件
            //中文字符占两个字节，其他字符为1个字节
            byte[] bytes = content.getBytes();
            //r为0表示所有分配的磁盘块都刚好占满
            int r = uitem.wpoint % Disk.MAXDISKBLOCK;
            if (r != 0) {
                //r表示最后一个磁盘块中已经写入数据的大小，如果不为0，我们还需向该磁盘块写入maxdiskblock-r的字节数据才能占满它
                DiskBlockNode node = sitem.fitem.fcb.flist.getLast();
                for (int i = 0; i < Disk.MAXDISKBLOCK - r && i < bytes.length; i++) {
                    node.content[r + i] = bytes[i];
                    //修改读写指针的数值
                    uitem.wpoint++;
                }
                //去掉bytes数组的已经写入的部分数据
                if (Disk.MAXDISKBLOCK - r < bytes.length)
                    bytes = Arrays.copyOfRange(bytes, Disk.MAXDISKBLOCK - r, bytes.length);
                else
                    bytes = new byte[]{};
            }
            if (bytes.length > 0) {
                //分配剩余的字节数据所需的磁盘块
                if (!requestDist(sitem.fitem.fcb.flist, bytes.length)) {
                    //out.println("存储空间已满，分配失败");
                    error(Error.DISK_OVERFLOW);
                    return;
                }
                //分配了空间之后，开始写入剩余数据
                int i = 0;
                //start表示在分配给该文件的磁盘块里面第一个没写入数据的磁盘块的下标
                int start = uitem.wpoint / Disk.MAXDISKBLOCK;
                for (DiskBlockNode node : sitem.fitem.fcb.flist) {
                    if (i >= start) {
                        node.content = Arrays.copyOfRange(bytes, (i - start) * node.maxlength, (i - start + 1) * node.maxlength);
                    }
                    i++;
                }
                //修改读写指针
                uitem.wpoint += bytes.length;
                //修改父目录的size
                sitem.fitem.parent.size+=bytes.length-sitem.fitem.fcb.size;
                //修改文件的size为bytes.length
                sitem.fitem.fcb.size = bytes.length;
                //修改文件的修改时间
                sitem.fitem.fcb.lastModifyTime = new Date().getTime();
                //修改目录的修改时间
                DirectoryItem item = sitem.fitem.parent;
                while (item!=null){
                   item.lastModifyTime = sitem.fitem.fcb.lastModifyTime;
                   item = item.parent;
                }
            }
        }
    }

    synchronized private void copy(String name, String path) throws IOException {
        //复制文件其实就是拿到对应文件的fcb中的flist,调用读函数读出flist中的内容
        //然后选定复制目录后，确定粘贴的时候，调用create命令,创建一个同名文件，大小为0，然后调用write写入数据，在写入中重新分配磁盘块
        //要想拿到fcb,就需要根据当前文件名和文件路径在目录中查找到对应的文件，然后返回该文件的内容
        //当粘贴的时候再去执行创建，写入的工作
        int uid = open(name, path, Mode.READ_ONLY);
        String content = read(uid);
        close(uid);
        System.out.println(content);
        out.println(buffer.size());
        buffer.put(id++, content);
    }

    private void paste(String name, String path, int id) throws IOException {
        //调用create命令,创建一个同名文件，大小为0，然后调用write写入数据，在写入中重新分配磁盘块
        create(name, path, Permission.RW_EXEC);
        int uid = open(name, path, Mode.WRITE_FIRST);
        write(uid, buffer.get(id));
        close(uid);
        buffer.remove(id);
    }

    private void rename(String name, String path, String newname) throws IOException {
        //重命名也就是根据文件名和文件路径查找到对应的目录项，然后修改其名字即可
        DirectoryItem result = getParent(path);
        for (DirectoryItem item : result.dirs) {
            if (item.name.equals(name)) {
                //如果当前该文件已被打开，则不允许重命名
                if (item.fcb.usecount > 0) {
                    System.out.println("文件占用中，不允许重命名");
                    //out.println("文件占用中，不允许重命名");
                    error(Error.USING_BY_OTHERS);
                    return;
                }
                item.name = newname;
                return;
            }
        }
    }

    /*
    public static void main(String[] args) throws IOException {
        Command command = new Command();
        //初始化
        command.initDisk();

        非指令测试集
        //创建一个用户
        command.createUser("li554","123456",Permission.RW_EXEC);
        //登录用户
        command.login("li554","123456");
        //先创建一个文件夹
        command.mkdir("test1","/li554/");
        command.mkdir("test2","/li554/");
        command.mkdir("test3","/li554/");
        //试试通过相对路径创建一个目录
        command.mkdir("test11","test1");
        //试试通过相对路径进入文件夹test11;
        command.cd("test1/test11");
        //试试在test11文件夹下创建一个文件
        command.create("a.txt","",Permission.RW_EXEC);
        //试试以覆盖写的方式打开一个文件
        int uid = command.open("a.txt","",Mode.WRITE_FIRST);
        //试试向文件a.txt写入一段字符串
        command.write(uid,"hello world");
        //试试关闭一个文件
        command.close(uid);
        //重新以读的方式打开a.txt,并读出数据
        int uid2 = command.open("a.txt","",Mode.READ_ONLY);
        System.out.println(command.read(uid2));
        command.close(uid2);
        //再次以添加写的方式打开a.txt
        int uid3 = command.open("a.txt","",Mode.WRITE_APPEND);
        command.write(uid3,"you are very beautiful");
        command.close(uid3);
        //再次读一下a.txt文件
        int uid4 = command.open("a.txt","",Mode.READ_ONLY);
        System.out.println(command.read(uid4));
        command.close(uid4);

        指令测试集
        reg li554 123455
        login li554 123456
        mkdir test1 /li554
        mkdir test11 test1
        create a.txt test1/test11
        open a.txt test1/test11 1
        write 0 hello world
        close 0
        open a.txt test1/test11 0
        read 0
        close 0
        open a.txt test1/test11 2
        write 0 you are beautiful
        close 0
        open a.txt test1/test11 0
        read 0
        close 0
        while (true){
            command.input();
        }
    }
*/
    /*
    private void input() throws IOException {
        Scanner input=new Scanner(System.in);
        String[] temp = input.nextLine().split("\\s+");
        String[] a = temp[0].equals("")?Arrays.copyOfRange(temp,1,temp.length):temp;
        String cmd = a[0];
        System.out.println(cmd);
        processCmd(cmd,a);
    }
    */
    private void processCmd(String cmd, String[] a) throws IOException {
        switch (cmd) {
            case "reg": {
                String name = a[1];
                String psw = a[2];
                createUser(name, psw, Permission.RW_EXEC);
            }
            break;
            case "login": {
                String name = a[1];
                String psw = a[2];
                login(name, psw);
            }
            break;
            case "dir": {
                dir();
            }
            break;
            case "mkdir": {
                String name = a[1];
                String path = a[2];
                mkdir(name, path);
            }
            break;
            case "rmdir": {
                String name = a[1];
                String path = a[2];
                rmdir(name, path);
            }
            break;
            case "cd": {
                String path = a[0];
                cd(path);
            }
            case "create": {
                String name = a[1];
                String path = a[2];
                create(name, path, Permission.RW_EXEC);
            }
            break;
            case "delete": {
                String name = a[1];
                String path = a[2];
                delete(name, path);
            }
            break;
            case "open": {
                String name = a[1];
                String path = a[2];
                int mode = Integer.parseInt(a[3]);
                int uid = open(name, path, mode);
                System.out.println("当前打开文件的uid为:" + uid + "\n请在操作该文件时输入该uid");
                //out.println(uid);
            }
            break;
            case "close": {
                int uid = Integer.parseInt(a[1]);
                close(uid);
            }
            break;
            case "read": {
                int uid = Integer.parseInt(a[1]);
                System.out.println(read(uid));
            }
            break;
            case "write": {
                int uid = Integer.parseInt(a[1]);
                String content = a[2];
                write(uid, content);
            }
            break;
            case "copy": {
                String name = a[1];
                String path = a[2];
                copy(name, path);
            }
            break;
            case "paste": {
                String name = a[1];
                String path = a[2];
                int id = Integer.parseInt(a[2]);
                paste(name, path, id);
            }
            break;
            case "rename": {
                String name = a[1];
                String path = a[2];
                String newname = a[3];
                rename(name, path, newname);
            }
            break;
            //后面是与前端数据显示相关的函数
            case "getDirectory": {
                getDirectory();
            }
            break;
            case "getTableData":{
                DirectoryItem root = getParent(a[1]);
                getTableData(root);
            }
        }
    }

    private void getDirectory() {
        /*
        一个文件对象，它有children和label两个属性，其中label为文件或目录名
        children表示该目录下的子项（文件或目录）
         */
        String json = JSON.toJSONString(disk.sroot);
        System.out.println(json);
        out.println(json);
    }

    private void getTableData(DirectoryItem root){
        JSONArray array = new JSONArray();
        if (root==null){
            JSONObject object = new JSONObject();
            object.put("filename",disk.sroot.name);
            object.put("modtime",disk.sroot.lastModifyTime);
            object.put("type","文件夹");
            object.put("size",disk.sroot.size);
            array.add(0,object);
        }else{
            for (DirectoryItem item:root.dirs){
                JSONObject object = new JSONObject();
                object.put("filename",item.name);
                if (item.tag==Tag.DIRECTORY_TYPE){
                    object.put("modtime",item.lastModifyTime);
                    object.put("type","文件夹");
                    object.put("size",item.size);
                }else if (item.tag==Tag.FILE_TYPE){
                    object.put("modtime",item.fcb.lastModifyTime);
                    object.put("type","文件");
                    object.put("size",item.fcb.size);
                }
                array.add(0,object);
            }
        }
        out.println(array.toJSONString());
    }
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/html;charset=utf-8");
        String cmd = req.getParameter("cmd");
        String[] params = req.getParameterValues("param");
        System.out.println(cmd);
        out = resp.getWriter();
        processCmd(cmd,params);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doGet(req, resp);
    }
}
