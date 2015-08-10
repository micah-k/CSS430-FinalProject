 import java.util.*;

 public class FileSystem extends Thread
 {
    private SuperBlock superBlock;
    private Directory directory;
    private FileTable fileTable;

    

    public FileSystem(int blocks)
    {
        superBlock = new SuperBlock(blocks);
        directory = new Directory(superBlock.totalInodes);
        fileTable = new FileTable(directory);

    }

    public FileTableEntry open(String filename, String mode)
    {

    }

    public synchronized int read(int fd, byte[] buffer)
    {

    }

    public synchronized int write(int fd, byte[] buffer)
    {

    }

    public synchronized int close(int fd)
    {

    }

    public int fsize(int fd)
    {

    }

    public int seek(int fd, int offset, int whence)
    {

    }

    public int format(int files)
    {

    }

    public synchronized int delete(String fileName)
    {

    }
 }