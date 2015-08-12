import java.util.*;

public class Inode {
    public final static int iNodeSize = 32;        // fix to 32 bytes
    public final static int directSize = 11;       // # direct pointers
    // Changed to public; why would final static variables need to be private?

    public int length;                              // file size in bytes
    public short count;                             // # file-table entries pointing to this
    public short flag;                              // 0 = unused, 1 = used, ...
    public short direct[] = new short[directSize];  // direct pointers
    public short indirect;                          // a indirect pointer
    public short iNumber;

    public static final int ERROR = -1;

    public final static short UNUSED = 0;           // Flag preset codes for Inode.
    public final static short USED = 1;
    public final static short READ = 2;
    public final static short WRITE = 3;
    public final static short DELETE = 4;   

    public Inode()
    {                                      // a default constructor
        length = 0;
        count = 0;
        flag = USED;
        for ( int i = 0; i < directSize; i++ )
            direct[i] = -1;
        indirect = -1;
    }

    // Retrieve Inode from disk.
    public Inode(short iNumber)
    {
        this.iNumber = iNumber;

        int block = iNumber / 16 + 1; // 16 Inodes in a block. Add 1 to account for rounding down in division.

        byte[] inodeData = new byte[Disk.blockSize]; // In computer development there is no way to read a portion of a block from, or write a portion to, the disk.
        SysLib.rawread(block, inodeData);

        int offset = (iNumber % 16) * iNodeSize; // Start offset pointer at head of the node's position in the block.

        length = SysLib.bytes2int(inodeData, offset); // Load variables in order. Length...
        offset += 4;
        count = SysLib.bytes2short(inodeData, offset); // Count...
        offset += 2;
        flag = SysLib.bytes2short(inodeData, offset); // Flag...
        offset += 2;

        for (int i = 0; i < directSize; i++, offset += 2) // Direct pointers...
        {
            direct[i] = SysLib.bytes2short(inodeData, offset);
        }
        indirect = SysLib.bytes2short(inodeData, offset); // Indirect pointer...
    }

    // Save to disk as the iNumber-th Inode.
    public void toDisk(short iNumber)
    {
        int block = iNumber / 16 + 1; // 16 Inodes in a block. Add 1 to account for rounding down in division.

        byte[] inodeData = new byte[Disk.blockSize]; // In computer development there is no way to read a portion of a block from, or write a portion to, the disk.
        SysLib.rawread(block, inodeData);

        int offset = (iNumber % 16) * iNodeSize; // Start offset pointer at head of the node's position in the block.
        
        SysLib.int2bytes(length, inodeData, offset); // Save variables in order. Length...
        offset += 4;
        SysLib.short2bytes(count, inodeData, offset); // Count...
        offset += 2;
        SysLib.short2bytes(flag, inodeData, offset); // Flag...
        offset += 2;
        for (int i = 0; i < directSize; i++, offset += 2) // Direct pointers...
        {
            SysLib.short2bytes(direct[i], inodeData, offset);
        }
        SysLib.short2bytes(indirect, inodeData, offset); // Indirect pointer...

        SysLib.rawwrite(block, inodeData); // Write the block back to the disk.
    }

    public short blockFromSeekPtr(int seekPtr)
    {
        if (seekPtr < 0)
            return ERROR;

        short seekBlock = (short)(seekPtr / Disk.blockSize);
        SysLib.cout("Seek block: (" + seekBlock + ") ");

        if (seekBlock < directSize)
        {
            short directBlock = direct[seekBlock];
            if(directBlock == ERROR)
            {
                SysLib.cout("No direct block here. ");
                return ERROR;
            }
            return directBlock;
        }

        else
        {
            if(indirect == ERROR)
            {
                SysLib.cout("No indirect block. ");
                return ERROR;
            }

            byte[] data = new byte[Disk.blockSize];
            SysLib.rawread(indirect, data);

            short indirectBlock = SysLib.bytes2short(data, seekBlock - directSize);
            if(indirectBlock == 0)
            {
                SysLib.cout("Bad indirect pointer. ");
                return ERROR;
            }
            return indirectBlock;
        }
    }

    public int addBlock(short block)
    {
        // Check for space in direct block.
        for (int i = 0; i < directSize; i++)
        {
            if (direct[i] <= 0)
            {
                direct[i] = block;
                return 0; // Success
            }
        }

        // No space in direct; try indirect next.
        if(indirect != ERROR)
        {
            byte[] data = new byte[Disk.blockSize];
            SysLib.rawread(indirect, data);
            for(short offset = 0; offset < Disk.blockSize; offset += 2)
            {
                //The next free indirect will be -1
                if (SysLib.bytes2short(data, offset) <= 0)
                {
                    //write the block number to the byte array
                    SysLib.short2bytes(block, data, offset);
                    //write the block back to disk return success condition on disk
                    return SysLib.rawwrite(indirect, data);
                }
            }
        }
        return ERROR;
    }
}