 import java.util.*;

 public class FileSystem extends Thread
 {
    private SuperBlock superBlock;
    private Directory directory;
    private FileStructureTable fileTable;



    public FileSystem(int blocks)
    {
        superBlock = new SuperBlock(blocks);
        directory = new Directory(superBlock.totalInodes);
        fileTable = new FileStructureTable(directory);

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
        if((myTcb = scheduler.getMyTcb()) != null)
            return myTcb.getFtEnt(fd).inode.length;
        return ERROR;
    }

    public int seek(int fd, int offset, int whence)
    {

    }

    public int format(int files)
    {
        if (files > 0){
            superblock.format(files);
            //directory = new Directory(files);
            //filetable = new FileTable(directory);
            return 0;
        }
        return -1;
    }

    public synchronized int delete(String filename)
    {
        int iNumber = directory.namei(filename);
        if (iNumber == ERROR) return ERROR;

        Inode inode = new Inode(iNumber);
        // Flag block for deletion.
        inode.flag = Inode.DELETE;
        if (!directory.ifree((short)iNumber)) return ERROR;
        inode.count = 0;
        inode.flag = Inode.USED;
        inode.toDisk(iNumber);
        return 0;
    }


    private boolean deallocAllBlocks(FileTableEntry fte)
    {
        Vector<Short> blocksFreed = new Vector<Short>();

        // Clear direct blocks.
        for (int i = 0; i < directSize; i++)
        {
            if (fte.inode.direct[i] > 0)
            {
                blocksFreed.add(fte.inode.direct[i]);
                fte.inode.direct[i] = -1;
            }
        }

        // Read in the indirect block.
        byte[] indirectBlock = new byte[Disk.blockSize];
        SysLib.rawread(indirect, indirectBlock);

        // Clear all blocks pointed to by the indirect block.
        for (int i = 0; i < Disk.blockSize / 2; i++)
        {
            short cur = SysLib.bytes2short(indirectBlock, i);
            //If its a valid block, reset it and add it to the return vector
            if (cur > 0)
            {
                //write 0 to the index block at this pos to invalidate it
                SysLib.int2bytes(0, indirectBlock, i);
                //save it to the return vector
                blocksFreed.add(new Short(cur));
            }
        }

        //Write the now zeroed indirect block back to disk
        SysLib.rawwrite(indirect, indirectBlock);
        toDisk(iNumber);

        // Return all freed blocks to the superblock freelist.
        for (int i = 0; i < blocksFreed.size(); i++)
            superblock.returnBlock((int)blocksFreed.elementAt(i));

        return true;
    }
 }