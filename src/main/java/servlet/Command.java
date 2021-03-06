package servlet;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        buffer = new HashMap<>();
        try {
            //创建一个用户
            createUser("li554","123456");
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
            create("a.txt","");
            //试试以覆盖写的方式打开一个文件
            long uid = open("a.txt","",Mode.WRITE_FIRST);
            //试试向文件a.txt写入一段字符串
            write(uid,"hello world");
            //试试关闭一个文件
            close(uid);
            //重新以读的方式打开a.txt,并读出数据
            long uid2 = open("a.txt","",Mode.READ_ONLY);
            System.out.println(read(uid2));
            close(uid2);
            //再次以添加写的方式打开a.txt
            long uid3 = open("a.txt","",Mode.WRITE_APPEND);
            write(uid3,"you are very beautiful");
            close(uid3);
            //再次读一下a.txt文件
            long uid4 = open("a.txt","",Mode.READ_ONLY);
            System.out.println(read(uid4));
            close(uid4);
        }catch (IOException e){
            e.printStackTrace();
        }
    }

    private String error(int n){
        switch (n){
            case Error.DUPLICATION:
                return "重名";
            case Error.DISK_OVERFLOW:
                return "磁盘已满，分配失败";
            case Error.PATH_NOT_FOUND:
                return "未找到指定路径";
            case Error.PERMISSION_DENIED:
                return "权限不足";
            case Error.USING_BY_OTHERS:
                return "文件被其他进程占用";
            case Error.UNKNOWN:
                return "未知错误";
        }
        return "ok";
    }

    private void createUser(String uname, String psw) {
        User user = new User(uname, psw);
        disk.sroot.dirs.add(user.uroot);
        disk.usertable.add(user);
        //初始化用户的文件访问表
        for (Map.Entry<Long, FCB> entry:disk.fcbList.entrySet()){
            user.visitList.put(entry.getKey(),new DomainItem(entry.getKey(),1,0,0));
        }
        //每当用户创建一个文件的时候，默认对文件有可读可写可执行的权限
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

    private int delete(DirectoryItem file, DirectoryItem parent) {
        if (check_permission(file.fcbid, -1)) {
            recoverDist(disk.fcbList.get(file.fcbid));
            parent.dirs.remove(file);
            long time = new Date().getTime();
            while (parent!=null){
                parent.lastModifyTime = time;
                parent = parent.parent;
            }
            //删除一个对应的表项
            current_user.visitList.remove(file.fcbid);
            return Success.DELETE;
        } else {
            return Error.PERMISSION_DENIED;
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

    private boolean check_permission(long fcbid, int mode) {
        //检查权限需要知道当前的操作和当前操作的文件
        DomainItem item = current_user.visitList.get(fcbid);
        int permission = item.R+item.W*2+item.E*4;
        switch (permission){
            case Permission.READ_ONLY:
                if (mode != Mode.READ_ONLY) {
                    return false;
                }
                break;
            case Permission.WRITE_ONLY:
                if (mode!= Mode.WRITE_FIRST||mode!=Mode.WRITE_APPEND){
                    return false;
                }
                break;
        }
        return true;
    }

    private int cd(String path) {
        DirectoryItem result = getParent(path);
        if (result!=null) {
            current_dir = result;
            return Success.CD;
        } else{
            return Error.PATH_NOT_FOUND;
        }
    }

    private void dir() {
        for (DirectoryItem item : current_dir.dirs) {
            System.out.println(item.name);
            //out.println(item.name);
        }
    }

    private int mkdir(String name, String path) {
        //首先检查是否重名
        DirectoryItem result = getParent(path);
        if (result == null) {
            return Error.PATH_NOT_FOUND;
        }
        //在获得的目录中查找是否已经存在该名字的文件，找到则提示重名错误
        for (DirectoryItem item : result.dirs) {
            if (item.name.equals(name)) {
                return Error.DUPLICATION;
            }
        }
        DirectoryItem item = new DirectoryItem(name,Tag.DIRECTORY_TYPE);
        item.parent = result;
        long time = new Date().getTime();
        item.creatTime = time;
        result.dirs.add(item);
        while (result!=null){
            result.lastModifyTime = time;
            result = result.parent;
        }
        return Success.MKDIR;
    }

    private int rmdir(String name, String path) throws IOException {
        DirectoryItem result = getParent(path);
        if (result == null) {
            return Error.PATH_NOT_FOUND;
        }
        //在获得的目录中查找对应的目录
        for (DirectoryItem item : result.dirs) {
            if (item.name.equals(name)) {
                rmdir(item);
                result.dirs.remove(item);
                long time = new Date().getTime();
                while (result!=null){
                    result.lastModifyTime = time;
                    result = result.parent;
                }
                return Success.RMDIR;
            }
        }
        return Error.UNKNOWN;
    }

    private int create(String name, String path) throws IOException {
        //首先检查是否重名
        DirectoryItem result = getParent(path);
        if (result == null) {
            return Error.PATH_NOT_FOUND;
        }
        //在获得的目录中查找是否已经存在该名字的文件，找到则提示重名错误
        for (DirectoryItem item : result.dirs) {
            if (item.name.equals(name)) {
                return Error.DUPLICATION;
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
            disk.fcbList.put(fcb.creatTime,fcb);
            DirectoryItem item = new DirectoryItem(name,Tag.FILE_TYPE);
            item.fcbid = fcb.creatTime;
            item.parent = result;
            result.dirs.add(item);
            while (result!=null){
                result.lastModifyTime = fcb.creatTime;
                result = result.parent;
            }
            current_user.visitList.put(item.fcbid,new DomainItem(item.fcbid,1,1,1));
            return Success.CREATE;
        } else {
            return Error.DISK_OVERFLOW;
        }
    }

    private int delete(String name, String path) throws IOException {
        //首先直接在系统打开文件表中查找该文件，获取打开计数器的值
        for (SystemOpenFile file : disk.softable.values()) {
            if (file.filename.equals(name) && file.opencount > 0) {
                return Error.USING_BY_OTHERS;
            }
        }
        DirectoryItem result = getParent(path);
        if (result == null) {
            return Error.PATH_NOT_FOUND;
        }
        for (DirectoryItem item : result.dirs) {
            if (item.name.equals(name)) {
                //没有被占用的话，判断一下用户是否有删除文件的权限
                return delete(item, result);
            }
        }
        return Error.UNKNOWN;
    }

    private long open(String name, String path, int mode) throws IOException {     //传入的是一个引用对象
        DirectoryItem result = getParent(path);
        if (result == null) {
            return Error.PATH_NOT_FOUND;
        }
        //找到所在目录后，从目录项中读取该文件的信息，首先判断是否有打开权限
        for (DirectoryItem item : result.dirs) {
            if (item.name.equals(name)) {
                if (check_permission(item.fcbid, mode)) {
                    //权限足够，接着检查是否当前进程已经打开了该文件
                    for (UserOpenFile uof : current_user.uoftable.values()) {
                        if (uof.filename.equals(name)) {
                            return uof.uid;
                        }
                    }
                    //没有打开，那么将该文件的相关信息填入到用户进程打开文件表和系统进程打开文件表，同时将文件的使用计数增加1
                    disk.fcbList.get(item.fcbid).usecount++;
                    UserOpenFile userOpenFile = new UserOpenFile();
                    userOpenFile.filename = name;
                    userOpenFile.mode = mode;
                    userOpenFile.rpoint = 0;
                    userOpenFile.uid = new Date().getTime();
                    userOpenFile.sid = userOpenFile.uid;
                    current_user.uoftable.put(userOpenFile.uid,userOpenFile);
                    SystemOpenFile systemOpenFile = new SystemOpenFile();
                    systemOpenFile.sid = userOpenFile.uid;
                    systemOpenFile.filename = name;
                    //打开计数器增加 1
                    systemOpenFile.opencount += 1;
                    //将该文件的目录项指针存在系统打开文件表中
                    systemOpenFile.fitem = item;
                    disk.softable.put(systemOpenFile.sid,systemOpenFile);
                    //最终应该返回一个文件描述符，也就是该文件在用户进程打开文件表中的uid
                    return userOpenFile.uid;
                } else {
                    return Error.PERMISSION_DENIED;
                }
            }
        }
        return Error.UNKNOWN;
    }

    private void close(long uid) {
        //关闭文件,需要提供一个文件描述符,系统在用户进程中的打开文件表找到对应文件，然后删除该文件项
        //并且根据获得的sid查找系统打开文件表，如果系统打开文件表中对应表项的打开计数器的值>1那么-1，否则，删除表项
        try {
            UserOpenFile uitem = current_user.uoftable.get(uid);
            SystemOpenFile sitem = disk.softable.get(uitem.sid);
            current_user.uoftable.remove(uid);
            if (sitem.opencount > 1) {
                sitem.opencount--;
            } else {
                disk.softable.remove(sitem.sid);
            }
        } catch (IndexOutOfBoundsException e) {
            System.out.println("文件已经关闭，无需再次关闭");
        }
    }

    private JSONObject read(long uid) throws UnsupportedEncodingException {
        UserOpenFile uitem = current_user.uoftable.get(uid);
        JSONObject object = new JSONObject();
        object.put("code",0);
        object.put("content","");
        if (uitem.mode == Mode.WRITE_FIRST || uitem.mode == Mode.WRITE_APPEND) {
            object.replace("code",String.valueOf(Error.PERMISSION_DENIED));
        } else {
            SystemOpenFile sitem = disk.softable.get(uitem.sid);
            StringBuilder content = new StringBuilder();
            for (DiskBlockNode node : disk.fcbList.get(sitem.fitem.fcbid).flist) {
                String str = new String(node.content);
                Pattern pattern = Pattern.compile("([^\u0000]*)");
                Matcher matcher = pattern.matcher(str);
                if(matcher.find(0)){
                    content.append(new String(matcher.group(1).getBytes("utf-8")));
                }
            }
            object.replace("content",content.toString());
        }
        return object;
    }

    private int write(long uid, String content) {
        UserOpenFile uitem = current_user.uoftable.get(uid);
        if (uitem.mode == Mode.READ_ONLY) {
            return Error.PERMISSION_DENIED;
        } else {
            SystemOpenFile sitem = disk.softable.get(uitem.sid);
            if (uitem.mode == Mode.WRITE_FIRST || uitem.mode==Mode.READ_WRITE) {
                //如果是重写，那么先回收分配的磁盘块，然后重新根据大小赋予新的磁盘块
                recoverDist(disk.fcbList.get(sitem.fitem.fcbid));
                uitem.wpoint = 0;
            }else{
                uitem.wpoint = disk.fcbList.get(sitem.fitem.fcbid).size;
            }
            //现在已经获取到了该文件，通过前端传来的的文本值，我们将该值写入文件
            //中文字符占两个字节，其他字符为1个字节
            byte[] bytes = content.getBytes();
            //r为0表示所有分配的磁盘块都刚好占满
            int r = uitem.wpoint % Disk.MAXDISKBLOCK;
            //start表示在分配给该文件的磁盘块里面第一个没写入数据的磁盘块的下标
            int start = uitem.wpoint / Disk.MAXDISKBLOCK;
            if (r != 0||start<disk.fcbList.get(sitem.fitem.fcbid).flist.size()-1)
            {
                int k = 0;
                for (int j=start;j<disk.fcbList.get(sitem.fitem.fcbid).flist.size();j++){
                    DiskBlockNode node = disk.fcbList.get(sitem.fitem.fcbid).flist.get(j);
                    if (j==start){
                        for (int i = 0; i < node.maxlength - r && k < bytes.length; i++) {
                            node.content[r + i] = bytes[k++];
                            //修改读写指针的数值
                            uitem.wpoint++;
                        }
                    }else{
                        for (int i=0;i< node.maxlength && k< bytes.length;i++){
                            node.content[i] = bytes[k++];
                            uitem.wpoint++;
                        }
                    }
                }
                //去掉bytes数组的已经写入的部分数据
                if (k < bytes.length)
                    bytes = Arrays.copyOfRange(bytes, k, bytes.length);
                else
                    bytes = new byte[]{};
            }
            if (bytes.length > 0) {
                //分配剩余的字节数据所需的磁盘块
                if (!requestDist(disk.fcbList.get(sitem.fitem.fcbid).flist, bytes.length)) {
                    return Error.DISK_OVERFLOW;
                }
                //分配了空间之后，开始写入剩余数据
                int i = 0;
                for (DiskBlockNode node : disk.fcbList.get(sitem.fitem.fcbid).flist) {
                    if (i >= start) {
                        node.content = Arrays.copyOfRange(bytes, (i - start) * node.maxlength, (i - start + 1) * node.maxlength);
                    }
                    i++;
                }
                //修改读写指针
                uitem.wpoint += bytes.length;
                //修改父目录的size
                sitem.fitem.parent.size+=bytes.length-disk.fcbList.get(sitem.fitem.fcbid).size;
                //修改文件的size为bytes.length
                disk.fcbList.get(sitem.fitem.fcbid).size = bytes.length;
                //修改文件的修改时间
                disk.fcbList.get(sitem.fitem.fcbid).lastModifyTime = new Date().getTime();
                //修改目录的修改时间
                DirectoryItem item = sitem.fitem.parent;
                while (item!=null){
                   item.lastModifyTime = disk.fcbList.get(sitem.fitem.fcbid).lastModifyTime;
                   item.size += disk.fcbList.get(sitem.fitem.fcbid).size;
                   item = item.parent;
                }
            }
        }
        return Success.WRITE;
    }

    synchronized private JSONObject copy(String name, String path) throws IOException {
        //复制文件其实就是拿到对应文件的fcb中的flist,调用读函数读出flist中的内容
        //然后选定复制目录后，确定粘贴的时候，调用create命令,创建一个同名文件，大小为0，然后调用write写入数据，在写入中重新分配磁盘块
        //要想拿到fcb,就需要根据当前文件名和文件路径在目录中查找到对应的文件，然后返回该文件的内容
        //当粘贴的时候再去执行创建，写入的工作
        long uid = open(name, path, Mode.READ_ONLY);
        JSONObject object = read(uid);
        object.put("bufferid",buffer.size());
        if (object.getIntValue("code")>=0)
            buffer.put(id++, object.getString("content"));
        close(uid);
        return object;
    }

    private int paste(String name, String path, int id) throws IOException {
        //调用create命令,创建一个同名文件，大小为0，然后调用write写入数据，在写入中重新分配磁盘块
        int code1,code2;
        code1 = create(name, path);
        long uid = open(name, path, Mode.WRITE_FIRST);
        code2 = write(uid, buffer.get(id));
        close(uid);
        buffer.remove(id);
        if (code1==Success.CREATE&&code2==Success.WRITE){
            return Success.PASTE;
        }else{
            return Error.UNKNOWN;
        }
    }

    private void search(JSONArray array,String pattern,String path,DirectoryItem root) {
        for (DirectoryItem item : root.dirs) {
            if (Pattern.matches(pattern, item.name)) {
                JSONObject object = new JSONObject();
                object.put("value",item.name+"   "+path);
                object.put("name",item.name);
                object.put("type",item.tag==Tag.FILE_TYPE?"文件":"文件夹");
                object.put("path",path);
                array.add(object);
            }
            if (item.dirs!=null)
                search(array, pattern, path+"/"+item.name, item);
        }
    }

    private String getSearchData(String q,int mode) throws IOException {
        //层序遍历
        //确定全局搜索还是当前文件夹
        //全局搜索
        String pattern = ".*"+q+".*";
        JSONArray array = new JSONArray();
        String path = "";
        if (mode==0){
            search(array,pattern,path,disk.sroot);
        }else{
            search(array,pattern,path,current_dir);
        }
        return array.toJSONString();
    }

    private int rename(String name, String path, String newname) throws IOException {
        //重命名也就是根据文件名和文件路径查找到对应的目录项，然后修改其名字即可
        //首先直接在系统打开文件表中查找该文件，获取打开计数器的值
        DirectoryItem result = getParent(path);
        for (SystemOpenFile file : disk.softable.values()) {
            if (file.filename.equals(name) && file.fitem.parent==result) {
                return Error.USING_BY_OTHERS;
            }
        }
        for (DirectoryItem item : result.dirs) {
            if (item.name.equals(newname)) {
                return Error.DUPLICATION;
            }
        }
        for (DirectoryItem item : result.dirs) {
            if (item.name.equals(name)) {
                item.name = newname;
                return Success.RENAME;
            }
        }
        return Error.UNKNOWN;
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
    private void processCmd(String[] a) throws IOException {
        String cmd = a[0];
        switch (cmd) {
            case "reg": {
                String name = a[1];
                String psw = a[2];
                createUser(name, psw);
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
                int code = mkdir(name, path);
                JSONObject object = new JSONObject();
                object.put("code",code);
                object.put("msg",error(code));
                out.println(object.toJSONString());
            }
            break;
            case "rmdir": {
                String name = a[1];
                String path = a[2];
                int code = rmdir(name, path);
                JSONObject object = new JSONObject();
                object.put("code",code);
                object.put("msg",error(code));
                out.println(object.toJSONString());
            }
            break;
            case "cd": {
                String path = a[1];
                int code = cd(path);
                JSONObject object = new JSONObject();
                object.put("code",code);
                object.put("msg",error(code));
                out.println(object.toJSONString());
            }
            break;
            case "create": {
                String name = a[1];
                String path = a[2];
                int code = create(name, path);
                JSONObject object = new JSONObject();
                object.put("code",code);
                object.put("msg",error(code));
                out.println(object.toJSONString());
            }
            break;
            case "delete": {
                String name = a[1];
                String path = a[2];
                int code = delete(name, path);
                JSONObject object = new JSONObject();
                object.put("code",code);
                object.put("msg",error(code));
                out.println(object.toJSONString());
            }
            break;
            case "open": {
                String name = a[1];
                String path = a[2];
                int mode = Integer.parseInt(a[3]);
                long code = open(name, path, mode);
                JSONObject object = new JSONObject();
                object.put("code",code);
                object.put("msg",error((int) code));
                out.println(object.toJSONString());
            }
            break;
            case "close": {
                Long uid = Long.parseLong(a[1]);
                close(uid);
            }
            break;
            case "read": {
                Long uid = Long.parseLong(a[1]);
                JSONObject object = read(uid);
                int code = object.getIntValue("code");
                object.put("msg",error(code));
                out.println(object.toJSONString());
            }
            break;
            case "write": {
                Long uid = Long.parseLong(a[1]);
                String content = a[2];
                System.out.println(content);
                int code = write(uid, content);
                JSONObject object = new JSONObject();
                object.put("code",code);
                object.put("msg",error(code));
                out.println(object.toJSONString());
            }
            break;
            case "copy": {
                String name = a[1];
                String path = a[2];
                JSONObject object = copy(name, path);
                int code = object.getIntValue("code");
                object.put("msg",error(code));
                out.println(object.toJSONString());
            }
            break;
            case "paste": {
                String name = a[1];
                String path = a[2];
                int id = Integer.parseInt(a[3]);
                JSONObject object = new JSONObject();
                if (id==-1) {
                    object.put("code",-1);
                    object.put("msg","当前没有可粘贴文件");
                }else{
                    int code = paste(name, path, id);
                    object.put("code",code);
                    object.put("msg",error(code));
                }
                out.println(object.toJSONString());
            }
            break;
            case "rename": {
                String name = a[1];
                String path = a[2];
                String newname = a[3];
                int code = rename(name, path, newname);
                JSONObject object = new JSONObject();
                object.put("code",code);
                object.put("msg",error(code));
                out.println(object.toJSONString());
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
            break;
            case "getSearchData":{
                String q = a[1];
                int mode = Integer.parseInt(a[2]);
                out.println(getSearchData(q,mode));
            }
            break;
            case "showProperty":{
                String name = a[1];
                String path = a[2];
                DirectoryItem result = getParent(path);
                for (DirectoryItem item:result.dirs){
                    if (item.name.equals(name)){
                        out.println("<div>名称:"+item.name+"</div>");
                        if (item.tag==Tag.FILE_TYPE){
                            out.println("<div>类型:"+"文件"+"</div>");
                            out.println("<div>创建时间:"+disk.fcbList.get(item.fcbid).creatTime+"</div>");
                            out.println("<div>修改时间:"+disk.fcbList.get(item.fcbid).lastModifyTime+"</div>");
                            out.println("<div>路径:"+a[2]+"/"+a[1]+"</div>");
                            out.println("<div>大小:"+disk.fcbList.get(item.fcbid).size+"</div>");
                        }else{
                            out.println("<div>类型:"+"文件夹"+"</div>");
                            out.println("<div>创建时间:"+item.creatTime+"</div>");
                            out.println("<div>修改时间:"+item.lastModifyTime+"</div>");
                            out.println("<div>路径:"+a[2]+"/"+a[1]+"</div>");
                            out.println("<div>大小:"+item.size+"</div>");
                        }
                    }
                }
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
                    object.put("modtime",disk.fcbList.get(item.fcbid).lastModifyTime);
                    object.put("type","文件");
                    object.put("size",disk.fcbList.get(item.fcbid).size);
                }
                array.add(0,object);
            }
        }
        out.println(array.toJSONString());
    }

    public String[] toStringArray(JSONArray array) {
        if(array==null)
            return null;
        String[] arr=new String[array.size()];
        for(int i=0; i<arr.length; i++) {
            arr[i]=array.getString(i);
        }
        return arr;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/html;charset=utf-8");
        InputStreamReader insr = new InputStreamReader(req.getInputStream(),"utf-8");
        String result = "";
        int respInt = insr.read();
        while(respInt!=-1) {
            result +=(char)respInt;
            respInt = insr.read();
        }
        JSONObject jsonRet = JSONObject.parseObject(result);
        out = resp.getWriter();
        JSONArray array = jsonRet.getJSONArray("param");
        String[] a = toStringArray(array);
        processCmd(a);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doGet(req, resp);
    }
}
