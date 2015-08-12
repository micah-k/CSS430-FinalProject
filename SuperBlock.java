class SuperBlock {
    public int totalBlocks; // the number of disk blocks
    public int totalInodes; // the number of inodes
    public int freeList;    // the block number of the free list's head
   
    public SuperBlock( int diskSize ) {
        byte[] superBlock = new byte[Disk.blockSize];
        SysLib.rawread(0, superBlock);
        totalBlocks = SysLib.bytes2int(superBlock, 0);
        totalInodes = SysLib.bytes2int(superBlock, 4);
        freeList = SysLib.bytes2int(superBlock, 8);

        // Verify disk contents
        if (!(totalBlocks == diskSize && totalInodes > 0 && freeList >= 2))
        {
            totalBlocks = diskSize;
            format(64);
        }
    }

    // Used in unverified construction from disk file, and in file system formatting.
    public synchronized void format(int files)
    {
        byte[] data = new byte[512];  // Local superblock to be saved to disk
        totalBlocks = 1000;                 // Blocks in disk
        totalInodes = files;                // Files (inodes) to format
        freeList = files / 16 + 1;          // Start the free list at the block after the last file to create.

        // If the number of files to create is a multiple of 16, then the superblock will add an extra inode in the previously chosen first free block.
        if (files % 16 == 0) freeList++;    // In this case, move the pointer to the next one.

        // Load the info into the local superblock.
        SysLib.int2bytes(totalBlocks, data, 0);
        SysLib.int2bytes(totalInodes, data, 4);
        SysLib.int2bytes(freeList, data, 8);

        // Save the superblock to the disk.
        SysLib.rawwrite(0, data);

        // Reset local superblock to save on data costs. Negligible issue on modern computers, but a good habit to have for more memory-intensive situations.
        for (int i = freeList; i < totalBlocks; i++)
        {
            // Fill block with 0.
            for (int j = 0; j < Disk.blockSize; j++)
            {
                data[j] = (byte)0;
            }

            // Write a pointer to the next free block position, if one exists.
            if (i != totalBlocks - 1)
                SysLib.int2bytes(i + 1, data, 0);

            //Save free block to the disk.
            SysLib.rawwrite(i, data);
        }
    }

    // Save the superblock
    public void sync()
    {
        byte[] superBlock = new byte[Disk.blockSize];
        SysLib.rawread(0, superBlock);
        SysLib.int2bytes(totalBlocks, superBlock, 0);
        SysLib.int2bytes(totalInodes, superBlock, 4);
        SysLib.int2bytes(freeList, superBlock, 8);
        SysLib.rawwrite(0, superBlock);
    }

    // Take a free block from the freelist
    public short claimBlock()
    {
        int result = freeList;                  // Save the current head index
        byte[] data = new byte[Disk.blockSize]; // Read in the data from the first free block
        SysLib.rawread(freeList, data);

        freeList = SysLib.bytes2int(data, 0);   // Move the head index to the next space.

        SysLib.int2bytes(0, data, 0);           // Clear the index after it's been copied out.

        SysLib.rawwrite(result, data);          // Write back the cleared file.

        sync();                                 // Superblock has changed; update disk.
        
        return (short)result;                          // Return the index.
    }

    // Return a block to head of freelist
    public void returnBlock(int blockNumber)
    {
        byte[] data = new byte[512];        // Byte array of data for transfer.
        for(int i = 0; i < Disk.blockSize; i++)
        {
            data[i] = (byte)0;
        }

        SysLib.int2bytes(freeList, data, 0);
        SysLib.rawwrite(blockNumber, data); // Write back returned block.

        freeList = blockNumber;
    }
}