import java.util.*;

public class FileStructureTable
{
    private Vector<FileTableEntry> table;           // the actual entity of this file table
    private Directory dir;                          // the root directory 

    public FileStructureTable(Directory directory)
    {
        table = new Vector<FileTableEntry>( );
        dir = directory;
    }

    // major public methods
    public synchronized FileTableEntry falloc(String filename, String mode)
    {
        short iNumber = (filename.equals("/") ? 0 : dir.namei(filename));
        Inode inode;

        if (iNumber >= 0) // if the file exists
        {
            inode = new Inode(iNumber);
            if (mode.equals("r"))
            {
                if (inode.flag == Inode.UNUSED || inode.flag == Inode.USED || inode.flag == Inode.READ)
                    inode.flag = Inode.READ;
                else if (inode.flag == Inode.WRITE)                    // Wait for file to be readable
                    try { wait(); } catch (InterruptedException e) {}
                else // inode.flag == Inode.DELETE
                    return null;
            }

            else // mode.equals(ww || w+ || a)
            {
                if(inode.flag == Inode.UNUSED || inode.flag == Inode.USED)
                    inode.flag = Inode.WRITE;
                else if (inode.flag == Inode.WRITE || inode.flag == Inode.READ) // Wait for file to be writeable
                    try { wait(); } catch (InterruptedException e) {}
                else // inode.flag == Inode.DELETE
                    return null;
            }
        }
        else // iNumber == -1, file doesn't exist
        {      
            iNumber = dir.ialloc(filename); // Create file
            inode = new Inode();
        }

        inode.count++; // Add a reference to this file
        inode.toDisk(iNumber); // Save the inode to the disk
        FileTableEntry e = new FileTableEntry(inode, iNumber, mode);    // Create a new entry,
        table.addElement(e);                                            // Store it,
        return e;                                                       // And return it.
    }

    public synchronized boolean ffree(FileTableEntry e)
    {
        if (table.removeElement(e))
        {
            e.inode.count--;
            if (e.inode.flag == Inode.READ || e.inode.flag == Inode.WRITE)
                notify();
            e.inode.toDisk(e.iNumber);
            return true;
        }
        return false;
    }

    public synchronized boolean fempty()
    {
        return table.isEmpty();  // return if table is empty 
    }                            // should be called before starting a format

  
    public synchronized FileTableEntry getFtEnt(int i)
    {
        if (i > 1 && i < table.size() + 2) return table.elementAt(i-2); //First two fds in TCB aren't stored in file table.
        return null;
    }
  
    public synchronized int getFd(FileTableEntry fte)
    {
        for (int i = 0; i < table.size(); i++)
        {
            if (table.elementAt(i) == fte)
                return i + 2; //First two fds in TCB aren't stored in file table.
        }
        return -1;
    }
}
