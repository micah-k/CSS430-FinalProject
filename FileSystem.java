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
        fileTable.ffree(dir);
    }

    public FileTableEntry open(String filename, String mode)
    {
        //Check if it is a new file before falloc because falloc will create a
        //new file if one does not exist
        boolean newFile = directory.namei(filename) == -1;
        FileTableEntry fte = fileTable.falloc(filename, mode);

        // Can't use a switch statement because Strings compare diferently.
        if (mode.equals("a"))
        {
            fte.seekPtr = fte.inode.length; // Can't use seek in open, because no fd exists yet.
        }
        else if (mode.equals("w"))
        {
            fte.seekPtr = 0;
            deallocAllBlocks(fte.inode);
            newFile = true;
        }
        else if (mode.equals("w+"))
        {
            fte.seekPtr = 0;
        }
        else //mode.equals("r")
        {
            if (newFile) 
                return null;  
            fte.seekPtr = 0;
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
            SysLib.cout("Read: Invalid file descriptor (" + fd + "). ");
            return ERROR;
        }

        boolean loop = true;
        int bytesRead = ERROR;
        fte.count++;

        while (loop)
        {
            switch(fte.inode.flag)
            {
                case Inode.WRITE:
                    try { wait(); } catch (InterruptedException e) {} // Wait for whatever's writing to stahp.
                    break; // Let's try this again.
                case Inode.UNUSED:
                case Inode.DELETE: // Y u do dis.
                    SysLib.cout("File unused or marked for deletion. ");
                    loop = false;
                    break;
                default: // UNUSED, USED, READ
                    // Flag block as read so other threads don't interfere.
                    fte.inode.flag = Inode.READ;

                    bytesRead = readFromFtEnt(fte, buffer);

                    // Decrement count, if necessary

                    fte.inode.flag = Inode.USED;                    
                    if (fte.count > 0) notifyAll(); // If there's another child thread waiting, wake it up
                    loop = false;
            }
        }

        fte.count--;                    
        return bytesRead;
    }

    private synchronized int readFromFtEnt(FileTableEntry fte, byte[] buffer)
    {
        byte[] data = new byte[Disk.blockSize];
        int bytesRead = 0;
        int readLength = 0;
        int cpyStart = 0;
        int iteration = 0;

        while (bytesRead < buffer.length) // While there's space in the buffer to read into,
        {
            iteration++;
            SysLib.cout("Iteration (" + iteration + "). ");
            int block = blockFromSeekPtr(fte.seekPtr, fte.inode);

            // Error check,
            if (block == ERROR)
            {
                SysLib.cout("Could not find block from seek ptr (" + fte.seekPtr + ") in iteration (" + iteration + "). ");
                return ERROR;
            }

            //Read from disk,
            if (SysLib.rawread(block, data) == ERROR)
            {
                SysLib.cout("Error reading block (" + block + "). ");
                return ERROR;
            }

            // If last block isn't entirely full, dont read all of it.
            boolean lastBlock = (fte.inode.length - fte.seekPtr) < Disk.blockSize ||
                                (fte.inode.length - fte.seekPtr) == 0;
            SysLib.cout("Last block (" + (lastBlock ? "true" : "false") + "). ");
            readLength = (lastBlock ? (fte.inode.length - fte.seekPtr) : Disk.blockSize);
            SysLib.cout("Read length (" + readLength + "). ");

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
            SysLib.cout("Write: Invalid file descriptor (" + fd + "). ");
            return ERROR;
        }

        if(fte.mode.equals("r"))
        {
            SysLib.cout("Cannot write in read mode. ");
            return ERROR;
        }

        int bytesWritten = ERROR;
        fte.count++;
        boolean loop = true;

        while (loop)
        {
            switch(fte.inode.flag)
            {
                case Inode.WRITE:
                case Inode.READ:
                    // Inode occupied; can't write.
                    try { wait(); } catch (InterruptedException e){}
                    break; // Let's try this again.
                case Inode.DELETE:
                case Inode.UNUSED:
                    SysLib.cout("Inode deleted. ");
                    loop = false;
                    break;
                default: // USED
                    // Flag inode for writing.
                    fte.inode.flag = Inode.WRITE;

                    bytesWritten = writeFromFtEnt(fte, buffer);


                    // Seek ptr past EOF? File has grown; update length.
                    if (fte.seekPtr >= fte.inode.length)
                    {
                        // Seek ptr is at end of file, becomes new length.
                        fte.inode.length = fte.seekPtr;
                        fte.inode.toDisk(fte.iNumber);
                    }

                    fte.inode.flag = Inode.USED;
                    if (fte.count > 0) notifyAll(); // Notify waiting threads, if they exist.
                    loop = false;
            }
        }

        fte.count--;
        return bytesWritten;
    }

    private synchronized int writeFromFtEnt(FileTableEntry fte, byte[] buffer)
    {
        short block;
        int bytesWritten = 0;
        int blockOffset = fte.seekPtr % Disk.blockSize;
        byte[] data = new byte[Disk.blockSize];
        short inodeOffset;
        int iteration = 0;

        while (bytesWritten < buffer.length)
        {
            iteration++;
            SysLib.cout("Iteration (" + iteration + "). ");
            block = blockFromSeekPtr(fte.seekPtr, fte.inode);
            inodeOffset = (short)(fte.seekPtr / Disk.blockSize);
            if (inodeOffset >= Inode.directSize - 1 && fte.inode.indirect <= 0)
            {
                // Grab a free block for the index.
                short index = superBlock.claimBlock();
                if (index == ERROR)
                {
                    SysLib.cout("No available free blocks. ");
                    return ERROR;
                }

                // Set the indirect block.
                fte.inode.indirect = index;
                // Save to disk.
                fte.inode.toDisk(fte.iNumber);
            }

            // Variable used multiple times; copy to local to prevent excess aritmetic.
            int remainingBytes = buffer.length - bytesWritten;
            SysLib.cout("Remaining bytes (" + remainingBytes + "). ");

            // If the next block doesn't exist, claim a free block to fill.
            if (block == ERROR || (bytesWritten % Disk.blockSize > 0 && remainingBytes > 0))
            {
                if (block == ERROR)
                    SysLib.cout("Block error; freeing block. ");
                block = superBlock.claimBlock();
                // No free blocks?
                if (block == ERROR)
                {
                    SysLib.cout("No available free blocks. ");
                    return ERROR;
                }

                if (fte.inode.addBlock(block) == ERROR)
                {
                    SysLib.cout("Could not add block to inode. ");
                    return ERROR;
                }

                fte.inode.toDisk(fte.iNumber);
            }
    
            // Load the block,
            SysLib.cout("Loading block (" + block + "). ");
            SysLib.rawread(block, data);

            // Write one block from the buffer. If there's less than a block left to write in the buffer, just fill the remaining space.
            int writeLength = ((remainingBytes < (Disk.blockSize - blockOffset)) ? remainingBytes : (Disk.blockSize - blockOffset));
            System.arraycopy(buffer, bytesWritten, data, blockOffset, writeLength);
            SysLib.cout("Write length (" + writeLength + "). ");
            
            // Save the block,
            SysLib.rawwrite(block, data);

            // Next contestant.
            block++;
            bytesWritten += writeLength;
            fte.seekPtr += writeLength;
            // Block offset starts at 0 for new blocks.
            blockOffset = 0; 
        }
        return bytesWritten;
    }

    public synchronized int close(int fd)
    {
        FileTableEntry fte = convertFdToFtEnt(fd);
        if(fte == null)
        {
            SysLib.cout("Close: Invalid file descriptor (" + fd + "). ");
            return ERROR; // UNUSED, DELETE
        }

        // If the file has an open on it, wait for it to finish.
        while(fte.count > 1)
        {
            try { wait(); } catch (InterruptedException e){}
        }

        if(fte.inode.flag != Inode.USED)
        {
            SysLib.cout("COUNT EQUALS 1 BUT FLAG NOT SET TO USED IN CLOSE. ");
        }

        // Flag and write the file to disk.
        return fileTable.ffree(fte) ? 0 : ERROR;
    }

    public int fsize(int fd)
    {
        FileTableEntry fte = convertFdToFtEnt(fd);
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
                return ERROR;
        }
        if (seek < 0) return ERROR;

        //set the seek pointer in the entry
        fte.seekPtr = seek;
        return fte.seekPtr;
    }

    public int format(int files)
    {
        if (files > 0)
        {
            superBlock.format(files);
            directory = new Directory(files);
            fileTable = new FileStructureTable(directory);
            return 0;
        }
        return ERROR;
    }

    public synchronized int delete(String filename)
    {
        short iNumber = directory.namei(filename);
        if (iNumber == ERROR) return ERROR;


        Inode inode = new Inode(iNumber);

        while(inode.count > 0)
        {
            try { wait(); } catch (InterruptedException e){}            
        }

        directory.ifree(iNumber);
        inode.count = 0;
        inode.length = 0;
        inode.flag = Inode.UNUSED;
        deallocAllBlocks(inode);
        return 0;
    }


    private void deallocAllBlocks(Inode inode)
    {
        Vector<Short> blocksFreed = new Vector<Short>();

        // Clear direct blocks.
        for (int i = 0; i < Inode.directSize; i++)
        {
            if (inode.direct[i] > 0)
            {
                blocksFreed.add(new Short(inode.direct[i]));
                inode.direct[i] = -1;
            }
        }

        if(inode.indirect >= 0)
        {
            // Read in the indirect block.
            byte[] indirectBlock = new byte[Disk.blockSize];
            SysLib.rawread(inode.indirect, indirectBlock);

            // Clear all blocks pointed to by the indirect block.
            for (int i = 0; i < Disk.blockSize / 2; i++)
            {
                short cur = SysLib.bytes2short(indirectBlock, i);
                //If its a valid block, reset it and add it to the return vector
                if (cur > 0)
                {
                    //save it to the return vector
                    blocksFreed.add(new Short(cur));
                }
            }
            blocksFreed.add(new Short(inode.indirect));
            inode.indirect = -1;
        }

        inode.toDisk(inode.iNumber);

        // Return all freed blocks to the superblock freelist.
        for (int i = 0; i < blocksFreed.size(); i++)
            superBlock.returnBlock((int)blocksFreed.elementAt(i));
    }

    private short blockFromSeekPtr(int seekPtr, Inode inode)
    {
        if (seekPtr < 0)
            return ERROR;

        int seekBlock = seekPtr / Disk.blockSize;

        if (seekBlock < inode.directSize)
        {
            short directBlock = inode.direct[seekBlock];
            if(directBlock == ERROR)
            {
                directBlock = superBlock.claimBlock();
                if (directBlock == ERROR)
                {
                    SysLib.cout("No free blocks! ");
                    return ERROR;
                }
                inode.addBlock(directBlock);
            }
            return directBlock;
        }

        else
        {
            if(inode.indirect == ERROR)
            {
                inode.indirect = superBlock.claimBlock();
            }

            byte[] data = new byte[Disk.blockSize];
            SysLib.rawread(inode.indirect, data);

            short indirectBlock = SysLib.bytes2short(data, seekBlock - inode.directSize);
            if(indirectBlock == 0)
            {
                indirectBlock = superBlock.claimBlock();
                if (indirectBlock == ERROR)
                {
                    SysLib.cout("No free blocks! ");
                    return ERROR;
                }
                inode.addBlock(indirectBlock);
            }
            return indirectBlock;
        }
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