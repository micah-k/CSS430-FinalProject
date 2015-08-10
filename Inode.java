import java.util.*;

public class Inode {
    private final static int iNodeSize = 32;        // fix to 32 bytes
    private final static int directSize = 11;       // # direct pointers

    public int length;                              // file size in bytes
    public short count;                             // # file-table entries pointing to this
    public short flag;                              // 0 = unused, 1 = used, ...
    public short direct[] = new short[directSize];  // direct pointers
    public short indirect;                          // a indirect pointer

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
        int blockNumber = iNumber / 16 + 1; // 16 Inodes in a block. Add 1 to account for rounding down in division.

        byte[] inodeData = new byte[Disk.blockSize]; // In computer development there is no way to read a portion of a block from, or write a portion to, the disk.
        SysLib.rawread(blockNumber, inodeData);

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
        int blockNumber = iNumber / 16 + 1; // 16 Inodes in a block. Add 1 to account for rounding down in division.

        byte[] inodeData = new byte[Disk.blockSize]; // In computer development there is no way to read a portion of a block from, or write a portion to, the disk.
        SysLib.rawread(blockNumber, inodeData);

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

        SysLib.rawwrite(blockNumber, inodeData); // Write the block back to the disk.
    }
}