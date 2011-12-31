/*******************************************************************************
 * Copyright 2010 Cees De Groot, Alex Boisvert, Jan Kotek
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package jdbm;

import java.io.*;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Hashtable directory page.
 *
 * @author Alex Boisvert
 */
final class HTreeDirectory<K, V> {

    /**
     * Maximum number of children in a directory.
     * <p/>
     * (Must be a power of 2 -- if you update this value, you must also
     * update BIT_SIZE and MAX_DEPTH.)
     * <p/>
     * !!!! do not change this, it affects storage format, there are also magic numbers which relies on 255 !!!
     */
    static final int MAX_CHILDREN = 256;


    /**
     * Number of significant bits per directory level.
     */
    static final int BIT_SIZE = 8; // log2(256) = 8


    /**
     * Maximum number of levels (zero-based)
     * <p/>
     * (4 * 8 bits = 32 bits, which is the size of an "int", and as
     * you know, hashcodes in Java are "ints")
     */
    static final int MAX_DEPTH = 3; // 4 levels


    /**
     * Record ids of children nodes.
     */
    private long[] _children;


    /**
     * Depth of this directory page, zero-based
     */
    private byte _depth;


    /**
     * DB used to persist changes in directory and buckets
     */
    private transient DBAbstract _db;


    /**
     * This directory's record ID in the DB.  (transient)
     */
    private transient long _recid;

    protected final HTree<K, V> tree;

    /**
     * Public constructor used by serialization
     */
    public HTreeDirectory(HTree<K, V> tree) {
        this.tree = tree;
    }

    /**
     * Construct a HashDirectory
     *
     * @param depth Depth of this directory node.
     */
    HTreeDirectory(HTree<K, V> tree, byte depth) {
        this.tree = tree;
        _depth = depth;
        _children = new long[MAX_CHILDREN];
    }


    /**
     * Sets persistence context.  This method must be called before any
     * persistence-related operation.
     *
     * @param db    DB which stores this directory
     * @param recid Record id of this directory.
     */
    void setPersistenceContext(DBAbstract db, long recid) {
        this._db = db;
        this._recid = recid;
    }


    /**
     * Get the record identifier used to load this hashtable.
     */
    long getRecid() {
        return _recid;
    }


    /**
     * Returns whether or not this directory is empty.  A directory
     * is empty when it no longer contains buckets or sub-directories.
     */
    boolean isEmpty() {
        for (int i = 0; i < _children.length; i++) {
            if (_children[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the value which is associated with the given key. Returns
     * <code>null</code> if there is not association for this key.
     *
     * @param key key whose associated value is to be returned
     */
    V get(K key)
            throws IOException {
        int hash = hashCode(key);
        long child_recid = _children[hash];
        if (child_recid == 0) {
            // not bucket/node --> not found
            return null;
        } else {
            Object node = _db.fetch(child_recid, tree.SERIALIZER);
            // System.out.println("HashDirectory.get() child is : "+node);

            if (node instanceof HTreeDirectory) {
                // recurse into next directory level
                HTreeDirectory<K, V> dir = (HTreeDirectory<K, V>) node;
                dir.setPersistenceContext(_db, child_recid);
                return dir.get(key);
            } else {
                // node is a bucket
                HTreeBucket<K, V> bucket = (HTreeBucket) node;
                return bucket.getValue(key);
            }
        }
    }


    /**
     * Associates the specified value with the specified key.
     *
     * @param key   key with which the specified value is to be assocated.
     * @param value value to be associated with the specified key.
     * @return object which was previously associated with the given key,
     *         or <code>null</code> if no association existed.
     */
    Object put(final Object key, final Object value)
            throws IOException {
        if (value == null) {
            return remove(key);
        }
        int hash = hashCode(key);
        long child_recid = _children[hash];
        if (child_recid == 0) {
            // no bucket/node here yet, let's create a bucket
            HTreeBucket bucket = new HTreeBucket(tree, (byte) (_depth + 1));

            // insert (key,value) pair in bucket
            Object existing = bucket.addElement(key, value);

            long b_recid = _db.insert(bucket, tree.SERIALIZER);
            _children[hash] = b_recid;

            _db.update(_recid, this, tree.SERIALIZER);

            // System.out.println("Added: "+bucket);
            return existing;
        } else {
            Object node = _db.fetch(child_recid, tree.SERIALIZER);

            if (node instanceof HTreeDirectory) {
                // recursive insert in next directory level
                HTreeDirectory dir = (HTreeDirectory) node;
                dir.setPersistenceContext(_db, child_recid);
                return dir.put(key, value);
            } else {
                // node is a bucket
                HTreeBucket bucket = (HTreeBucket) node;
                if (bucket.hasRoom()) {
                    Object existing = bucket.addElement(key, value);
                    _db.update(child_recid, bucket, tree.SERIALIZER);
                    // System.out.println("Added: "+bucket);
                    return existing;
                } else {
                    // overflow, so create a new directory
                    if (_depth == MAX_DEPTH) {
                        throw new RuntimeException("Cannot create deeper directory. "
                                + "Depth=" + _depth);
                    }
                    HTreeDirectory dir = new HTreeDirectory(tree, (byte) (_depth + 1));
                    long dir_recid = _db.insert(dir, tree.SERIALIZER);
                    dir.setPersistenceContext(_db, dir_recid);

                    _children[hash] = dir_recid;
                    _db.update(_recid, this, tree.SERIALIZER);

                    // discard overflown bucket
                    _db.delete(child_recid);

                    // migrate existing bucket elements
                    ArrayList keys = bucket.getKeys();
                    ArrayList values = bucket.getValues();
                    int entries = keys.size();
                    for (int i = 0; i < entries; i++) {
                        dir.put(keys.get(i), values.get(i));
                    }

                    // (finally!) insert new element
                    return dir.put(key, value);
                }
            }
        }
    }


    /**
     * Remove the value which is associated with the given key.  If the
     * key does not exist, this method simply ignores the operation.
     *
     * @param key key whose associated value is to be removed
     * @return object which was associated with the given key, or
     *         <code>null</code> if no association existed with given key.
     */
    Object remove(Object key) throws IOException {
        int hash = hashCode(key);
        long child_recid = _children[hash];
        if (child_recid == 0) {
            // not bucket/node --> not found
            return null;
        } else {
            Object node = _db.fetch(child_recid, tree.SERIALIZER);
            // System.out.println("HashDirectory.remove() child is : "+node);

            if (node instanceof HTreeDirectory) {
                // recurse into next directory level
                HTreeDirectory dir = (HTreeDirectory) node;
                dir.setPersistenceContext(_db, child_recid);
                Object existing = dir.remove(key);
                if (existing != null) {
                    if (dir.isEmpty()) {
                        // delete empty directory
                        _db.delete(child_recid);
                        _children[hash] = 0;
                        _db.update(_recid, this, tree.SERIALIZER);
                    }
                }
                return existing;
            } else {
                // node is a bucket
                HTreeBucket bucket = (HTreeBucket) node;
                Object existing = bucket.removeElement(key);
                if (existing != null) {
                    if (bucket.getElementCount() >= 1) {
                        _db.update(child_recid, bucket, tree.SERIALIZER);
                    } else {
                        // delete bucket, it's empty
                        _db.delete(child_recid);
                        _children[hash] = 0;
                        _db.update(_recid, this, tree.SERIALIZER);
                    }
                }
                return existing;
            }
        }
    }

    /**
     * Calculates the hashcode of a key, based on the current directory
     * depth.
     */
    private int hashCode(Object key) {
        int hashMask = hashMask();
        int hash = key.hashCode();
        hash = hash & hashMask;
        hash = hash >>> ((MAX_DEPTH - _depth) * BIT_SIZE);
        hash = hash % MAX_CHILDREN;
        /*
        System.out.println("HashDirectory.hashCode() is: 0x"
                           +Integer.toHexString(hash)
                           +" for object hashCode() 0x"
                           +Integer.toHexString(key.hashCode()));
        */
        return hash;
    }

    /**
     * Calculates the hashmask of this directory.  The hashmask is the
     * bit mask applied to a hashcode to retain only bits that are
     * relevant to this directory level.
     */
    int hashMask() {
        int bits = MAX_CHILDREN - 1;
        int hashMask = bits << ((MAX_DEPTH - _depth) * BIT_SIZE);
        /*
        System.out.println("HashDirectory.hashMask() is: 0x"
                           +Integer.toHexString(hashMask));
        */
        return hashMask;
    }

    /**
     * Returns an enumeration of the keys contained in this
     */
    Iterator<K> keys()
            throws IOException {
        return new HDIterator(true);
    }

    /**
     * Returns an enumeration of the values contained in this
     */
    Iterator<V> values()
            throws IOException {
        return new HDIterator(false);
    }


    /**
     * Implement Externalizable interface
     */
    public void writeExternal(DataOutput out)
            throws IOException {
        out.writeByte(_depth);

        int zeroStart = 0;
        for (int i = 0; i < MAX_CHILDREN; i++) {
            if (_children[i] != 0) {
                zeroStart = i;
                break;
            }
        }

        out.write(zeroStart);
        if (zeroStart == MAX_CHILDREN)
            return;

        int zeroEnd = 0;
        for (int i = MAX_CHILDREN - 1; i >= 0; i--) {
            if (_children[i] != 0) {
                zeroEnd = i;
                break;
            }
        }
        out.write(zeroEnd);

        for (int i = zeroStart; i <= zeroEnd; i++) {
            LongPacker.packLong(out, _children[i]);
        }
    }


    /**
     * Implement Externalizable interface
     */
    public synchronized void readExternal(DataInputOutput in)
            throws IOException, ClassNotFoundException {
        _depth = in.readByte();
        _children = new long[MAX_CHILDREN];
        int zeroStart = in.readUnsignedByte();
        int zeroEnd = in.readUnsignedByte(); //TODO this may fit into one byte

        for (int i = zeroStart; i <= zeroEnd; i++) {
            _children[i] = LongPacker.unpackLong(in);
        }

    }

    public void defrag(DBStore r1, DBStore r2) throws IOException, ClassNotFoundException {
        for (long child : _children) {
            if (child == 0) continue;
            byte[] data = r1.fetchRaw(child);
            r2.forceInsert(child, data);
            Object t = tree.SERIALIZER.deserialize(new DataInputStream(new ByteArrayInputStream(data)));
            if (t instanceof HTreeDirectory) {
                ((HTreeDirectory) t).defrag(r1, r2);
            }
        }
    }


    ////////////////////////////////////////////////////////////////////////
    // INNER CLASS
    ////////////////////////////////////////////////////////////////////////

    /**
     * Utility class to enumerate keys/values in a HTree
     */
    class HDIterator<A> implements Iterator<A> {

        /**
         * True if we're iterating on keys, False if enumerating on values.
         */
        private boolean _iterateKeys;

        /**
         * Stacks of directories & last enumerated child position
         */
        private ArrayList _dirStack;
        private ArrayList _childStack;

        /**
         * Current HashDirectory in the hierarchy
         */
        private HTreeDirectory _dir;

        /**
         * Current child position
         */
        private int _child;

        /**
         * Current bucket iterator
         */
        private Iterator<A> _iter;

        private A next;

        /**
         * last item returned in next(), is used to remove() last item
         */
        private A last;

        private int expectedModCount;

        /**
         * Construct an iterator on this directory.
         *
         * @param iterateKeys True if iteration supplies keys, False
         *                    if iterateKeys supplies values.
         */
        HDIterator(boolean iterateKeys)
                throws IOException {
            _dirStack = new ArrayList();
            _childStack = new ArrayList();
            _dir = HTreeDirectory.this;
            _child = -1;
            _iterateKeys = iterateKeys;
            expectedModCount = tree.modCount;

            prepareNext();
            next = next2();

        }


        /**
         * Returns the next object.
         */
        public A next2() {
            A next = null;
            if (_iter != null && _iter.hasNext()) {
                next = _iter.next();
            } else {
                try {
                    prepareNext();
                } catch (IOException except) {
                    throw new IOError(except);
                }
                if (_iter != null && _iter.hasNext()) {
                    return next2();
                }
            }
            return next;
        }


        /**
         * Prepare internal state so we can answer <code>hasMoreElements</code>
         * <p/>
         * Actually, this code prepares an Enumeration on the next
         * Bucket to enumerate.   If no following bucket is found,
         * the next Enumeration is set to <code>null</code>.
         */
        private void prepareNext() throws IOException {
            long child_recid = 0;

            // get next bucket/directory to enumerate
            do {
                _child++;
                if (_child >= MAX_CHILDREN) {

                    if (_dirStack.isEmpty()) {
                        // no more directory in the stack, we're finished
                        return;
                    }

                    // try next node
                    _dir = (HTreeDirectory) _dirStack.remove(_dirStack.size() - 1);
                    _child = ((Integer) _childStack.remove(_childStack.size() - 1)).intValue();
                    continue;
                }
                child_recid = _dir._children[_child];
            } while (child_recid == 0);

            if (child_recid == 0) {
                throw new Error("child_recid cannot be 0");
            }

            Object node = _db.fetch(child_recid, tree.SERIALIZER);
            // System.out.println("HDEnumeration.get() child is : "+node);

            if (node instanceof HTreeDirectory) {
                // save current position
                _dirStack.add(_dir);
                _childStack.add(new Integer(_child));

                _dir = (HTreeDirectory) node;
                _child = -1;

                // recurse into
                _dir.setPersistenceContext(_db, child_recid);
                prepareNext();
            } else {
                // node is a bucket
                HTreeBucket bucket = (HTreeBucket) node;
                if (_iterateKeys) {
                    ArrayList keys2 = bucket.getKeys();
                    _iter = keys2.iterator();
                } else {
                    _iter = bucket.getValues().iterator();
                }
            }
        }


        public boolean hasNext() {
            return next != null;
        }


        public A next() {
            if (next == null) throw new NoSuchElementException();
            if (expectedModCount != tree.modCount)
                throw new ConcurrentModificationException();
            last = next;
            next = next2();
            return last;
        }


        public void remove() {
            if (last == null) throw new IllegalStateException();

            if (expectedModCount != tree.modCount)
                throw new ConcurrentModificationException();

            //TODO current delete behaviour may change node layout. INVESTIGATE if this can happen!
            tree.remove(last);
            last = null;
            expectedModCount++;
        }
    }

    public DBAbstract getRecordManager() {
        return _db;
    }
}

