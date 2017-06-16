package org.torch.utils.collection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Set;

import com.koloboke.collect.set.hash.HashObjSets;

public class HashSetBackedArrayList<E> implements List<E> {

    protected final ArrayList<E> arraylist = new ArrayList<>();
    protected final Set<E> hashset = HashObjSets.newMutableSet();

    @Override
    public int size() {
        return arraylist.size();
    }

    @Override
    public boolean isEmpty() {
        return arraylist.isEmpty();
    }

    @Override
    public boolean contains(final Object o) {
        return hashset.contains(o);
    }

    @Override
    public Object[] toArray() {
        return arraylist.toArray();
    }

    @Override
    public <T> T[] toArray(final T[] a) {
        return arraylist.toArray(a);
    }

    @Override
    public boolean add(final E e) {
        arraylist.add(e);
        hashset.add(e);
        return true;
    }

    @Override
    public boolean containsAll(final Collection<?> c) {
        return hashset.containsAll(c);
    }

    @Override
    public boolean addAll(final Collection<? extends E> c) {
        arraylist.addAll(c);
        hashset.addAll(c);
        return true;
    }

    @Override
    public boolean removeAll(final Collection<?> c) {
        final boolean result = arraylist.removeAll(c);
        if (result) {
            hashset.removeAll(c);
        }
        return result;
    }

    @Override
    public boolean retainAll(final Collection<?> c) {
        final boolean result = arraylist.retainAll(c);
        if (result) {
            hashset.retainAll(c);
        }
        return result;
    }

    @Override
    public void clear() {
        hashset.clear();
        arraylist.clear();
    }

    @Override
    public boolean remove(final Object o) {
        arraylist.remove(o);
        hashset.remove(o);
        return true;
    }

    @Override
    public E get(final int index) {
        return arraylist.get(index);
    }

    @Override
    public E remove(final int index) {
        final E e = arraylist.remove(index);
        hashset.remove(e);
        return e;
    }

    @Override
    public int indexOf(final Object o) {
        return arraylist.indexOf(o);
    }

    @Override
    public int lastIndexOf(final Object o) {
        return arraylist.lastIndexOf(o);
    }

    @Override
    public E set(final int index, final E element) {
        final E result = arraylist.set(index, element);
        hashset.add(element);
        return result;
    }

    @Override
    public void add(final int index, final E element) {
        arraylist.add(index, element);
        hashset.add(element);
    }

    @Override
    public boolean addAll(final int index, final Collection<? extends E> c) {
        final boolean result = arraylist.addAll(index, c);
        if (result) {
            hashset.addAll(c);
        }
        return result;
    }

    @Override
    public Iterator<E> iterator() {
        return listIterator();
    }

    @Override
    public ListIterator<E> listIterator() {
        return listIterator(0);
    }

    @Override
    public ListIterator<E> listIterator(final int index) {
        return new ListIteratorImpl<>(this, index);
    }

    @Override
    public List<E> subList(final int fromIndex, final int toIndex) {
        throw new UnsupportedOperationException();
    }

    protected static class ListIteratorImpl<E> implements ListIterator<E> {
        protected final HashSetBackedArrayList<E> list;
        protected int nextIndex;
        protected int lastRet;

        public ListIteratorImpl(final HashSetBackedArrayList<E> list, final int nextIndex) {
            this.list = list;
            this.nextIndex = nextIndex;
        }

        @Override
        public boolean hasNext() {
            return nextIndex < list.size();
        }

        @Override
        public boolean hasPrevious() {
            return nextIndex != 0;
        }

        @Override
        public int nextIndex() {
            return nextIndex;
        }

        @Override
        public int previousIndex() {
            return nextIndex - 1;
        }

        @Override
        public E next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return list.get(lastRet = nextIndex++);
        }

        @Override
        public E previous() {
            if (!hasPrevious()) {
                throw new NoSuchElementException();
            }
            return list.get(lastRet = --nextIndex);
        }

        @Override
        public void remove() {
            if (lastRet < 0) {
                throw new IllegalStateException();
            }
            list.remove(nextIndex = lastRet);
            lastRet = -1;
        }

        @Override
        public void set(final E e) {
            if (lastRet < 0) {
                throw new IllegalStateException();
            }
            list.set(lastRet, e);
        }

        @Override
        public void add(final E e) {
            list.set(nextIndex++, e);
            lastRet = -1;
        }
    }

}
