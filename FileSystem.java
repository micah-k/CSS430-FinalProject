 import java.util.*;

 public class FileSystem extends Thread
 {
    private SuperBlock superBlock;
    private Directory directory;
    private FileStructureTable fileTable;

    private TCB myTcb;

    public static final int ERROR = -1;

    public static final int SET = 0;
    public static final int CUR = 1;
    public static final int END = 2;

    public FileSystem(int blocks)
    {
        superBlock = new SuperBlock(blocks);
        directory = new Directory(superBlock.totalInodes);
        fileTable = new FileStructureTable(directory);

        // Open the base directory file from the disk.
        FileTableEntry dir = open("/", "r");

        // Check for data in the directory.
        int dirSize = dir.inode.length;
        if (dirSize > 0)
        {
            // If data has been stored, read it in.
            byte[] data = new byte[dirSize];
            readFromFtEnt(dir, data);
            directory.bytes2directory(data);
        }
        dir.inode.flag = Inode.USED;
        dir.inode.toDisk(dir.iNumber);
        fileTable.ffree(dir);
    }

    public FileTableEntry open(String filename, String mode)
    {
        //Check if it is a new file before falloc because falloc will create a
        //new file if one does not exist
        boolean newFile = directory.namei(filename) == -1;
        FileTableEntry fte = fileTable.falloc(filename, mode);
        short flag;

        // Can't use a switch statement because Strings compare diferently.
        if (mode.equals("a"))
        {
            fte.seekPtr = fte.inode.length; // Can't use seek in open, because no fd exists yet.
            flag = Inode.WRITE;
        }
        else if (mode.equals("w"))
        {
            fte.seekPtr = 0;
            deallocAllBlocks(fte);
            newFile = true;
            flag = Inode.WRITE;
        }
        else if (mode.equals("w+"))
        {
            fte.seekPtr = 0;
            flag = Inode.WRITE;
        }
        else //mode.equals("r")
        {
            if (newFile) 
                return null;  
            fte.seekPtr = 0;
            flag = Inode.READ;
        }

        // We only want to set the flag if we are the first one in
        if (fte.inode.count == 1)
        {
            fte.inode.flag = flag;
        }
        
        if (newFile)
        {
            // Claim a free block for the new direct block.
            short directBlock = superBlock.claimBlock();
            if (directBlock == ERROR)
            {
                return null; // No free blocks available.
            }

            fte.inode.addBlock(directBlock);
            fte.inode.toDisk(fte.iNumber); 
        }
        return fte;
    }

    public synchronized int read(int fd, byte[] buffer)
    {
        FileTableEntry fte = convertFdToFtEnt(fd);
        if(fte == null)
        {
            SysLib.cout("Invalid file descriptor (" + fd + "). ");
            return ERROR;
        }

        fte.count++;

        while (true)
        {
            switch(fte.inode.flag)
            {
                case Inode.WRITE:
                    try { wait(); } catch (InterruptedException e) {} // Wait for whatever's writing to stahp.
                    break; // Let's try this again.
                case Inode.DELETE: // Y u do dis.
                    fte.count--;
                    return ERROR; // Dun do dis.
                default: // UNUSED, USED, READ
                    // Flag block as read so other threads don't interfere.
                    fte.inode.flag = Inode.READ;

                    int bytesRead = readFromFtEnt(fte, buffer);

                    // Decrement count, if necessary
                    fte.count--;

                    // If there's another child thread waiting, wake it up
                    if (fte.count > 0) notifyAll();
                    else fte.inode.flag = Inode.USED; // This inode is no longer in a read state.
                    
                    return bytesRead;
            }
        }
    }

    private synchronized int readFromFtEnt(FileTableEntry fte, byte[] buffer)
    {
        byte[] data = new byte[Disk.blockSize];
        int bytesRead = 0;
        int readLength = 0;
        int cpyStart = 0;

        while (bytesRead < buffer.length) // While there's space in the buffer to read into,
        {
            int block = blockFromSeekPtr(fte.seekPtr, fte.inode);

            // Error check,
            if (block == ERROR) return ERROR;

            //Read from disk,
            if (SysLib.rawread(block, data) == ERROR) return ERROR;

            // If last block isn't entirely full, dont read all of it.
            boolean lastBlock = (fte.inode.length - fte.seekPtr) < Disk.blockSize ||
                                (fte.inode.length - fte.seekPtr) == 0;
            readLength = (lastBlock ? (fte.inode.length - fte.seekPtr) : Disk.blockSize);

            // Remaining data in one disk block,
            if (buffer.length < (512 - fte.seekPtr))
            {
                System.arraycopy(data, fte.seekPtr, buffer, 0, buffer.length); // Copy into buffer,
                bytesRead = buffer.length; //Increment read count,
            }

            // Remaining data in multiple blocks,
            else
            {
                System.arraycopy(data, 0, buffer, cpyStart, readLength); // Copy into buffer,
                bytesRead += readLength; //Increment read count,
            }

            cpyStart = cpyStart + readLength - 1;
            fte.seekPtr += readLength; // Can't use seek because fd may not exist yet if we're being called from the constructor.
        }

        return bytesRead;
    }

    public synchronized int write(int fd, byte[] buffer)
    {
        FileTableEntry fte = convertFdToFtEnt(fd);
        if(fte == null)
        {
            SysLib.cout("Invalid file descriptor (" + fd + "). ");
            return ERROR;
        }

        short block = blockFromSeekPtr(fte.seekPtr, fte.inode);
        int bytesWritten = 0;
        int blockOffset = fte.seekPtr % Disk.blockSize;
        fte.count++;

        while (true)
        {

            switch(fte.inode.flag)
            {
                case Inode.WRITE:
                case Inode.READ:
                    // Inode occupied; can't write.
                    if (fte.count > 1) // If in use, wait until open.
                        try { wait(); } catch (InterruptedException e){}
                    else // If not, must be a mistaken flag; change to unopened.
                        fte.inode.flag = Inode.USED;
                    break; // Let's try this again.
                case Inode.DELETE:
                    fte.count--;
                    SysLib.cout("Inode deleted. ");
                    return ERROR;
                default:
                    // Flag inode for writing.
                    fte.inode.flag = Inode.WRITE;

                    byte[] data = new byte[Disk.blockSize];
                    short inodeOffset;
                    while (bytesWritten < buffer.length)
                    {
                        inodeOffset = (short)(fte.seekPtr / Disk.blockSize);
                        if (inodeOffset >= Inode.directSize - 1 && fte.inode.indirect <= 0)
                        {
                            // Grab a free block for the index.
                            short index = superBlock.claimBlock();
                            if (index == ERROR)
                            {
                                SysLib.cout("No available free blocks. ");
                                return ERROR; // No available free block.
                            }

                            // Set the indirect block.
                            fte.inode.indirect = index;
                            // Save to disk.
                            fte.inode.toDisk(fte.iNumber);
                        }

                        // Variable used multiple times; copy to local to prevent excess aritmetic.
                        int remainingBytes = buffer.length - bytesWritten;

                        // If the next block doesn't exist, claim a free block to fill.
                        if (block == ERROR || (bytesWritten % Disk.blockSize > 0 && remainingBytes > 0))
                        {
                            block = superBlock.claimBlock();
                            // No free blocks?
                            if (block == ERROR)
                            {
                                SysLib.cout("No available free blocks. ");
                                return ERROR; // No available free block.
                            }

                            if (fte.inode.addBlock(block) == ERROR)
                            {
                                SysLib.cout("Could not add block to inode. ");
                                return ERROR; // No available free block.
                            }

                            fte.inode.toDisk(fte.iNumber);
                        }
                
                        // Load the block,
                        SysLib.rawread(block, data);

                        // Write one block from the buffer. If there's less than a block left to write in the buffer, just fill the remaining space.
                        int writeLength = ((remainingBytes < (Disk.blockSize - blockOffset)) ? remainingBytes : (Disk.blockSize - blockOffset));
                        System.arraycopy(buffer, bytesWritten, data, blockOffset, writeLength);
                        
                        // Save the block,
                        SysLib.rawwrite(block, data);

                        // Next contestant.
                        block++;
                        bytesWritten += writeLength;
                        fte.seekPtr += writeLength;
                        // Block offset starts at 0 for new blocks.
                        blockOffset = 0; 
                    }
                    // We've finished writing, so 
                    fte.count--;

                    // Seek ptr past EOF? File has grown; update length.
                    if (fte.seekPtr >= fte.inode.length)
                    {
                        // Seek ptr is at end of file, becomes new length.
                        fte.inode.length = fte.seekPtr;
                        fte.inode.toDisk(fte.iNumber);
                    }

                    if (fte.count > 0) notifyAll(); // Notify waiting threads, if they exist.
                    else fte.inode.flag = Inode.USED; // If not, reset flag.
                    return bytesWritten;
            }
        }
    }

    public synchronized int close(int fd)
    {
        FileTableEntry fte = convertFdToFtEnt(fd);
        if(fte == null) return ERROR;

        // If the file has an open on it, close the open.
        if (fte.count > 0)
            fte.count--;

        // If not, flag and write the file to disk.
        else
        {
            fte.inode.flag = Inode.USED;
            fte.inode.toDisk(fte.iNumber);
            return fileTable.ffree(fte) ? 0 : ERROR;
        }
        return 0;
    }

    public int fsize(int fd)
    {
        FileTableEntry fte = convertFdToFtEnt(fd);
        if((myTcb = Kernel.scheduler.getMyTcb()) != null)
            fd = myTcb.getFd(fte);
        if(fte == null) return ERROR;
        return fte.inode.length;
    }

    public int seek(int fd, int offset, int whence)
    {
        FileTableEntry fte = convertFdToFtEnt(fd);
        if(fte == null) return ERROR;
        int seek = fte.seekPtr;
        switch(whence)
        {
            case SET: 
                seek = offset;
                break;
            case CUR: 
                seek += offset;
                break;
            case END: 
                seek = fsize(fd) + offset;
                break;
            default:
                return -1;
        }
        if (seek < 0) {
            return -1;
        }
        //set the seek pointer in the entry
        fte.seekPtr = seek;
        return fte.seekPtr;
    }

    public int format(int files)
    {
        if (files > 0){
            superBlock.format(files);
            directory = new Directory(files);
            fileTable = new FileStructureTable(directory);
            return 0;
        }
        return -1;
    }

    public synchronized int delete(String filename)
    {
        short iNumber = directory.namei(filename);
        if (iNumber == ERROR) return ERROR;

        Inode inode = new Inode(iNumber);
        // Flag block for deletion.
        inode.flag = Inode.DELETE;
        if (!directory.ifree(iNumber)) return ERROR;
        inode.count = 0;
        inode.flag = Inode.UNUSED;
        inode.toDisk(iNumber);
        return 0;
    }


    private boolean deallocAllBlocks(FileTableEntry fte)
    {
        Vector<Short> blocksFreed = new Vector<Short>();

        // Clear direct blocks.
        for (int i = 0; i < Inode.directSize; i++)
        {
            if (fte.inode.direct[i] > 0)
            {
                blocksFreed.add(fte.inode.direct[i]);
                fte.inode.direct[i] = -1;
            }
        }

        // Read in the indirect block.
        byte[] indirectBlock = new byte[Disk.blockSize];
        SysLib.rawread(fte.inode.indirect, indirectBlock);

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
        SysLib.rawwrite(fte.inode.indirect, indirectBlock);
        fte.inode.toDisk(fte.iNumber);

        // Return all freed blocks to the superblock freelist.
        for (int i = 0; i < blocksFreed.size(); i++)
            superBlock.returnBlock((int)blocksFreed.elementAt(i));

        return true;
    }

    private short blockFromSeekPtr(int seekPtr, Inode inode)
    {
        if (seekPtr / Disk.blockSize < 0)
            return -1;
        else if (seekPtr / Disk.blockSize < inode.directSize)
            return inode.direct[seekPtr / Disk.blockSize];

        byte[] indirectBlock = new byte[Disk.blockSize];
        SysLib.rawread(inode.indirect, indirectBlock);
        return SysLib.bytes2short(indirectBlock, seekPtr / Disk.blockSize - inode.directSize);
    }

    private FileTableEntry convertFdToFtEnt(int fd)
    {
        FileTableEntry fte = null;
        if ((myTcb = Kernel.scheduler.getMyTcb()) != null)
        {
            fte = myTcb.getFtEnt(fd);
        }
        return fte;
    }
 }