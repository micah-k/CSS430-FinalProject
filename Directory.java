public class Directory
{
    private static int maxChars = 30; // max characters of each file name

    // Directory entries
    private int fsize[];         // each element stores a different file size.
    private char fnames[][];     // each element stores a different file name.

    public Directory(int maxInumber)
    {
        fsizes = new int[maxInumber];                   // maxInumber = max files
        for ( int i = 0; i < maxInumber; i++ ) 
            fsize[i] = 0;                               // all file size initialized to 0
        fnames = new char[maxInumber][maxChars];
        String root = "/";                              // entry(inode) 0 is "/"
        fsize[0] = root.length( );                      // fsize[0] is the size of "/".
        root.getChars( 0, fsizes[0], fnames[0], 0 );    // fnames[0] includes "/"
    }

    public int bytes2directory(byte data[])
    {
        // assumes data[] received directory information from disk
        // initializes the Directory instance with this data[]
        int offset = 0;

        for (int i = 0; i < fnames.length; i++, offset += maxChars * 2) // Store names, one at a time.
        {
            String fname = new String(data, offset, maxChars * 2);   // String has a built-in conversion function; far easier than converting each char one at a time.
            fname.getChars(0, )
        }

        for (int i = 0; i < fsizes.length; i++ , offset += 4) // Store sizes, one at a time.
        {
            SysLib.int2bytes(fsizes[i], result, offset);    // Use SysLib's provided int2bytes function.
        }
    }

    public byte[] directory2bytes()
    {
        // converts and return Directory information into a plain byte array
        // this byte array will be written back to disk
        // note: only meaningful directory information should be converted
        // into bytes.
        byte[] result = new byte[(fsizes.length * maxChars * 2) + (fsizes.length * 4)]; //fsizes.length == maxInumber, ints are 4 bytes each, chars are 2 bytes each
        int offset = 0;

        for (int i = 0; i < fnames.length; i++, offset += maxChars * 2) // Store names, one at a time.
        {
            String fname = new String(fnames[i]);   // String has a built-in conversion function; far easier than converting each char one at a time.
            byte[] bytes = fname.getBytes();        // Uses platform's default character set.
            System.arraycopy(bytes, 0, result, offset, bytes.length);
        }

        for (int i = 0; i < fsizes.length; i++ , offset += 4) // Store sizes, one at a time.
        {
            SysLib.int2bytes(fsizes[i], result, offset);    // Use SysLib's provided int2bytes function.
        }
    }

    public short ialloc(String filename)
    {
        // filename is the one of a file to be created.
        // allocates a new inode number for this filename
    }

    public boolean ifree(short iNumber)
    {
        // deallocates this inumber (inode number)
        // the corresponding file will be deleted.
        if(iNumber <= 0 || iNumber >= fsizes.length)
            return false;
        fnames[iNumber] = new char[maxChars];
        fsizes[iNumber] = 0;
    }

    public short namei(String filename)
    {
        // returns the inumber corresponding to this filename

    }
}