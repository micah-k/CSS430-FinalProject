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
            if (inode.flag == Inode.UNUSED)
                inode.flag = Inode.USED;
            else if (inode.flag == Inode.DELETE)
                return null;
        }
        else // iNumber == -1, file doesn't exist
        {      
            iNumber = dir.ialloc(filename); // Create file
            inode = new Inode();
            inode.iNumber = iNumber;
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
}
