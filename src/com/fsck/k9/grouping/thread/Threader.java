package com.fsck.k9.grouping.thread;

import java.text.MessageFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import android.util.Log;

import com.fsck.k9.K9;
import com.fsck.k9.grouping.MessageInfo;

/**
 * <a href="http://www.jwz.org/doc/threading.html">Jamie Zawinski's message
 * threading algorithm</a> implementation.
 */
public class Threader
{

    private static final Pattern RESPONSE_PATTERN = Pattern.compile(
            "^(Re|Fw|Fwd|Aw)(\\[\\d+\\])? *: *", Pattern.CASE_INSENSITIVE);

    /**
     * @param <T>
     * @param messages
     *            Never <code>null</code>.
     * @return First root or <code>null</code> if <tt>messages</tt> is empty.
     */
    public <T> Container<T> thread(final Collection<MessageInfo<T>> messages)
    {
        if (messages.isEmpty())
        {
            return null;
        }

        // 1. Index messages
        Map<String, Container<T>> containers = indexMessages(messages);

        // 2. Find the root set.
        final Container<T> firstRoot = findRoot(containers);

        // 3. Discard id_table. We don't need it any more.
        containers = null;

        // 4. Prune empty containers.
        final Container<T> fakeRoot = new Container<T>();
        addChild(fakeRoot, firstRoot);
        pruneEmptyContainer(null, fakeRoot, fakeRoot);

        // 5. Group root set by subject.
        groupRootBySubject(fakeRoot.getChild());

        return fakeRoot.getChild();

    }

    /**
     * If any two members of the root set have the same subject, merge them.
     * This is so that messages which don't have References headers at all still
     * get threaded (to the extent possible, at least.)
     * 
     * @param <T>
     * @param node
     */
    private <T> void groupRootBySubject(final Container<T> node)
    {
        // A. Construct a new hash table, subject_table, which associates
        // subject strings with Container objects.
        final Map<String, Container<T>> subjectTable = new HashMap<String, Container<T>>();

        // B. For each Container in the root set:
        for (Container<T> root = node; root != null; root = root.getNext())
        {
            // Find the subject of that sub-tree:
            final String subject = findSubject(root, true);

            if (subject.length() == 0)
            {
                // If the subject is now "", give up on this Container.
                continue;
            }

            // Add this Container to the subject_table if:
            final Container<T> previous = subjectTable.get(subject);
            if (previous == null)
            {
                // There is no container in the table with this subject, or
                subjectTable.put(subject, root);
            }
            else if (root.getMessage() == null && previous.getMessage() != null)
            {
                // This one is an empty container and the old one is not: the
                // empty one is more interesting as a root, so put it in the
                // table instead.
                subjectTable.put(subject, root);
            }
            else if (findSubject(previous, false).length() > subject.length()
                    && subject.equals(findSubject(root, false)))
            {
                // The container in the table has a ``Re:'' version of this
                // subject, and this container has a non-``Re:'' version of this
                // subject. The non-re version is the more interesting of the
                // two.
                subjectTable.put(subject, root);
            }
        }

        // C. Now the subject_table is populated with one entry for each subject
        // which occurs in the root set. Now iterate over the root set, and
        // gather together the difference.

        Container<T> next;
        // For each Container in the root set:
        for (Container<T> root = node; root != null; root = next)
        {
            // saving next now since current root might be removed from
            // siblings!
            next = root.getNext();

            // Find the subject of this Container (as above.)
            final String subject = findSubject(root, true);

            // Look up the Container of that subject in the table.
            final Container<T> match = subjectTable.get(subject);

            if (match == null || match == root)
            {
                // If it is null, or if it is this container, continue.
                continue;
            }

            // Otherwise, we want to group together this Container and the one
            // in the table. There are a few possibilities:

            final MessageInfo<T> thisMessage = root.getMessage();
            final MessageInfo<T> thatMessage = match.getMessage();
            final boolean thisEmpty = thisMessage == null;
            final boolean thatEmpty = thatMessage == null;
            if (thisEmpty && thatEmpty)
            {
                // If both are dummies, append one's children to the other, and
                // remove the now-empty container.
                addChild(root, match.getChild());
                removeChild(match);
            }
            else if (thisEmpty ^ thatEmpty)
            {
                // If one container is a empty and the other is not, make the
                // non-empty one be a child of the empty, and a sibling of the
                // other ``real'' messages with the same subject (the empty's
                // children.)
                if (thisEmpty)
                {
                    removeChild(match);
                    addChild(root, match);
                }
                else
                {
                    removeChild(root);
                    addChild(match, root);
                }
            }
            else
            {
                final boolean thatIsResponse = RESPONSE_PATTERN.matcher(thatMessage.getSubject())
                        .find();
                final boolean thisIsResponse = RESPONSE_PATTERN.matcher(thisMessage.getSubject())
                        .find();
                if (!thatEmpty && !thatIsResponse && thisIsResponse)
                {
                    // If that container is a non-empty, and that message's
                    // subject does not begin with ``Re:'', but this message's
                    // subject does, then make this be a child of the other.
                    removeChild(root);
                    addChild(match, root);
                }
                else if (!thatEmpty && thatIsResponse && !thisIsResponse)
                {
                    // If that container is a non-empty, and that message's
                    // subject begins with ``Re:'', but this message's subject
                    // does not, then make that be a child of this one -- they
                    // were misordered. (This happens somewhat implicitly, since
                    // if there are two messages, one with Re: and one without,
                    // the one without will be in the hash table, regardless of
                    // the order in which they were seen.)
                    removeChild(match);
                    addChild(root, match);
                }
                else
                {
                    // Otherwise, make a new empty container and make both msgs
                    // be a child of it. This catches the both-are-replies and
                    // neither-are-replies cases, and makes them be siblings
                    // instead of asserting a hierarchical relationship which
                    // might not be true.
                    final Container<T> newParent = new Container<T>();
                    spliceChild(match, newParent);
                    addChild(newParent, match);
                    removeChild(root);
                    addChild(newParent, root);
                }
            }
        }
    }

    /**
     * @param <T>
     * @param container
     * @param strip
     *            TODO
     * @return
     * @throws PatternSyntaxException
     */
    private <T> String findSubject(Container<T> container, final boolean strip)
            throws PatternSyntaxException
    {
        String subject;
        if (container.getMessage() != null)
        {
            // If there is a message in the Container, the subject is the
            // subject of that message.
            subject = container.getMessage().getSubject();
        }
        else
        {
            // If there is no message in the Container, then the Container
            // will have at least one child Container, and that Container
            // will have a message. Use the subject of that message instead.
            subject = container.getChild().getMessage().getSubject();
        }

        if (strip)
        {
            // Strip ``Re:'', ``RE:'', ``RE[5]:'', ``Re: Re[4]: Re:'' and so on.
            subject = stripSubject(subject);
        }

        return subject;
    }

    /**
     * @param subject
     * @return TODO
     * @throws PatternSyntaxException
     */
    private String stripSubject(final String subject) throws PatternSyntaxException
    {
        final Matcher matcher = RESPONSE_PATTERN.matcher(subject);
        int lastPrefix = -1;
        while (matcher.find())
        {
            lastPrefix = matcher.end();
        }
        if (lastPrefix > -1 && lastPrefix < subject.length() - 1)
        {
            return subject.substring(lastPrefix);
        }
        else
        {
            return subject;
        }
    }

    /**
     * Debug method, count the number of messages in the given tree.
     * 
     * @param <T>
     * @param node
     * @param count
     * @param countEmpty
     *            If <code>true</code>, empty messages are included in the sum.
     * @return Number of nodes (current (1) + descendants + <tt>count</tt>)
     */
    public static <T> int count(final Container<T> node, final int count, final boolean countEmpty)
    {
        if (node == null)
        {
            return 0;
        }
        int i = 0;
        if (countEmpty || node.getMessage() != null)
        {
            i = 1;
        }
        if (node.getChild() != null)
        {
            i = count(node.getChild(), i, countEmpty);
        }
        if (node.getNext() != null)
        {
            i = count(node.getNext(), i, countEmpty);
        }
        return count + i;
    }

    /**
     * Index the given message list into a {@link Map}&lt;{@link String},
     * {@link Container}&lt;<tt>T</tt>&gt;&gt; where:
     * <ul>
     * <li>the key is a Message-ID</li>
     * <li>the value is a corresponding {@link Container}, possibly empty (no
     * message) in case of reference tracking</li>
     * </ul>
     * 
     * @param <T>
     * @param messages
     *            Messages to index. Never <code>null</code>.
     * @return Resulting {@link Map}. Never <code>null</code>.
     */
    private <T> Map<String, Container<T>> indexMessages(final Collection<MessageInfo<T>> messages)
    {
        final Map<String, Container<T>> containers = new HashMap<String, Container<T>>();

        for (final MessageInfo<T> message : messages)
        {
            // A. If id_table contains an empty Container for this ID:
            final String id = message.getId();
            Container<T> container;
            if ((container = containers.get(id)) != null && container.getMessage() == null)
            {
                // Store this message in the Container's message slot.
                container.setMessage(message);
            }
            else
            {
                // Else:
                // Create a new Container object holding this message;
                // Index the Container by Message-ID in id_table.
                container = new Container<T>();
                container.setMessage(message);
                containers.put(id, container);
            }

            // B. For each element in the message's References field:
            Container<T> previous = null;
            for (final String reference : message.getReferences())
            {
                Container<T> referenceContainer;
                // Find a Container object for the given Message-ID:
                // If there's one in id_table use that;
                if ((referenceContainer = containers.get(reference)) == null)
                {
                    // Otherwise, make (and index) one with a null Message.
                    referenceContainer = new Container<T>();
                    containers.put(reference, referenceContainer);
                }

                if (previous != null)
                {
                    // Link the References field's Containers together in the
                    // order implied by the References header.

                    // If they are already linked, don't change the existing
                    // links.

                    // Do not add a link if adding that link would introduce a
                    // loop: that is, before asserting A->B, search down the
                    // children of B to see if A is reachable, and also search
                    // down the children of A to see if B is reachable. If
                    // either is already reachable as a child of the other,
                    // don't add the link.

                    if (!(reachable(previous, referenceContainer) || reachable(referenceContainer,
                            previous)))
                    {
                        if (referenceContainer.getParent() != null)
                        {
                            removeChild(referenceContainer);
                        }
                        addChild(previous, referenceContainer);
                    }
                }

                previous = referenceContainer;
            }

            if (previous != null)
            {
                // C. Set the parent of this message to be the last element in
                // References.

                // Note that this message may have a parent already: this can
                // happen because we saw this ID in a References field, and
                // presumed a parent based on the other entries in that field.
                // Now that we have the actual message, we can be more
                // definitive, so throw away the old parent and use this new
                // one. Find this Container in the parent's children list, and
                // unlink it.
                if (container.getParent() != null)
                {
                    removeChild(container);
                }
                addChild(previous, container);
            }

        }
        return containers;
    }

    /**
     * Walk over the elements of <tt>containers</tt>, and gather a list of the
     * Container objects that have no parents.
     * 
     * @param <T>
     * @param containers
     * @return First found root having no parent, as a linked list.
     *         <code>null</code> if none found.
     */
    private <T> Container<T> findRoot(final Map<String, Container<T>> containers)
    {
        Container<T> firstRoot = null;
        Container<T> lastRoot = null;
        for (final Container<T> c : containers.values())
        {
            if (c.getParent() == null)
            {
                if (firstRoot == null)
                {
                    firstRoot = c;
                }
                if (lastRoot != null)
                {
                    lastRoot.setNext(c);
                }
                lastRoot = c;
            }
        }
        return firstRoot;
    }

    /**
     * Prune empty containers. Recursively walk all containers under the root
     * set.
     * 
     * @param <T>
     * @param previous
     *            Previous sibling. If <code>null</code>, that means
     *            <tt>container</tt> is the first child
     * @param container
     *            The node to remove from its parent/sibling list and eventually
     *            replaced with its children
     * @param root
     *            Hierarchy root
     */
    private <T> void pruneEmptyContainer(final Container<T> previous, final Container<T> container,
            final Container<T> root)
    {
        boolean removed = false;
        boolean promoted = false;
        final Container<T> child = container.getChild();
        final Container<T> next = container.getNext();

        if (container.getMessage() == null)
        {
            if (child == null)
            {
                // A. If it is an empty container with no children, nuke it.

                removeChild(container);
                removed = true;
            }
            else if (child != null)
            {
                // B. If the Container has no Message, but does have children,
                // remove this container but promote its children to this level
                // (that is, splice them in to the current child list.)

                // Do not promote the children if doing so would promote them to
                // the root set -- unless there is only one child, in which
                // case, do.
                if ((container.getParent() != null && container.getParent() != root)
                        || child.getNext() == null)
                {
                    // not a root node OR only 1 child

                    spliceChild(container, child);
                    removed = true;
                    promoted = true;
                }
            }
        }

        // going deeper
        if (child != null && !promoted)
        {
            pruneEmptyContainer(null, child, root);
        }

        // going next
        if (promoted)
        {
            pruneEmptyContainer(previous, child, root);
        }
        else if (next != null)
        {
            pruneEmptyContainer(removed ? previous : container, next, root);
        }
    }

    /**
     * Add a child to a parent node.
     * 
     * <p>
     * As a concistency measure, new node (and its following siblings) will get
     * their old parent detached (element will be removed from parent children
     * list) and reset to the new parent.
     * </p>
     * 
     * @param <T>
     * @param parent
     *            Parent. Never <code>null</code>.
     * @param child
     *            Child to add. Never <code>null</code>.
     */
    private <T> void addChild(final Container<T> parent, final Container<T> child)
    {
        final Map<Container<T>, Boolean> currentChildren = new IdentityHashMap<Container<T>, Boolean>();

        Container<T> sibling;
        if ((sibling = parent.getChild()) == null)
        {
            // no children
            parent.setChild(child);
        }
        else
        {
            // at least one child, advancing
            while (sibling.getNext() != null)
            {
                currentChildren.put(sibling, Boolean.TRUE);
                sibling = sibling.getNext();
            }
            // last child, adding new sibling
            sibling.setNext(child);
        }

        // update the parent for the current node and its siblings

        // cache already verified parents for performance purpose
        final Map<Container<T>, Boolean> alreadyVerified = new IdentityHashMap<Container<T>, Boolean>();

        // don't verify new parent
        alreadyVerified.put(parent, Boolean.TRUE);

        for (Container<T> newSibling = child; newSibling != null; newSibling = newSibling.getNext())
        {
            // discard old parent (make sure each getParent() is consistent)
            final Container<T> oldParent = newSibling.getParent();
            if (oldParent != null && !alreadyVerified.containsKey(oldParent))
            {
                Container<T> previousOldSibling = null;
                for (Container<T> oldSibling = oldParent.getChild(); oldSibling != null; oldSibling = oldSibling
                        .getNext())
                {
                    if (newSibling == oldSibling)
                    {
                        if (previousOldSibling == null)
                        {
                            oldParent.setChild(null);
                        }
                        else
                        {
                            previousOldSibling.setNext(null);
                        }
                    }
                    previousOldSibling = oldSibling;
                }
                alreadyVerified.put(oldParent, Boolean.TRUE);
            }

            // old parent was verified, replacing
            newSibling.setParent(parent);

            currentChildren.put(newSibling, Boolean.TRUE);
            if (currentChildren.containsKey(newSibling.getNext()))
            {
                // circular reference detected!
                if (K9.DEBUG && Log.isLoggable(K9.LOG_TAG, Log.WARN))
                {
                    Log.w(K9.LOG_TAG, MessageFormat.format(
                            "Circular reference detected on {0} / parent: {1} / next: {2}",
                            newSibling, newSibling.getParent(), newSibling.getNext()));
                }
                newSibling.setNext(null);
                break;
            }
        }
    }

    /**
     * Remove a single node. Cancel the {@link Container#getNext()} link of the
     * removed node.
     * 
     * <p>
     * As a consistency measure following siblings of the removed child will get
     * their {@link Container#setParent(Container) parent} updated.
     * </p>
     * 
     * @param <T>
     * @param child
     *            Child to remove from its parent. Never <code>null</code>
     */
    private <T> void removeChild(final Container<T> child)
    {
        final Container<T> parent = child.getParent();
        child.setParent(null);
        if (parent.getChild() == null)
        {
            return;
        }
        Container<T> previous = null;
        for (Container<T> sibling = parent.getChild(); sibling != null; sibling = sibling.getNext())
        {
            if (sibling == child)
            {
                if (previous == null)
                {
                    parent.setChild(sibling.getNext());
                }
                else
                {
                    previous.setNext(sibling.getNext());
                }
            }
            else
            {
                // sanity measure, reset the parent to ensure consistency
                sibling.setParent(parent);
            }

            previous = sibling;
        }
        child.setNext(null);
    }

    /**
     * Replace <tt>oldChild</tt> with <tt>newChild</tt> (and its current
     * {@link Container#getNext() siblings}) in the current (<tt>odlChild</tt> 
     * 's) sibling list.
     * 
     * <p>
     * <tt>newChild</tt> and its siblings will get their
     * {@link Container#setParent(Container) parent} updated.
     * </p>
     * 
     * @param <T>
     * @param oldChild
     *            Node to remove. Never <code>null</code>.
     * @param newChild
     *            Node to insert. Never <code>null</code>.
     */
    private <T> void spliceChild(final Container<T> oldChild, final Container<T> newChild)
    {
        final Container<T> parent = oldChild.getParent();
        // final Container<T> oldNext = oldChild.getNext();
        // oldChild.setNext(null);
        // removeChild(oldChild);
        // addChild(parent, newChild);
        // addChild(parent, oldNext);
        Container<T> previous = null;
        boolean found = false;
        for (Container<T> sibling = parent.getChild(); sibling != null; sibling = sibling.getNext())
        {
            if (sibling == oldChild)
            {
                if (previous == null)
                {
                    parent.setChild(newChild);
                }
                else
                {
                    previous.setNext(newChild);
                }
                sibling = newChild;
                found = true;
            }
            if (found)
            {
                sibling.setParent(parent);
                if (sibling.getNext() == null)
                {
                    sibling.setNext(oldChild.getNext());
                    break;
                }
            }
            previous = sibling;
        }
        if (found)
        {
            oldChild.setNext(null);
            oldChild.setParent(null);
        }
    }

    /**
     * @param <T>
     * @param a
     *            Never <code>null</code>
     * @param b
     *            Never <code>null</code>
     * @return <code>true</code> if <tt>a</tt> is reachable as a descendant of
     *         <tt>b</tt> (or if they are the same), <code>false</code>
     *         otherwise
     */
    private <T> boolean reachable(Container<T> a, Container<T> b)
    {
        if (a == b)
        {
            return true;
        }
        for (Container<T> child = b.getChild(); child != null; child = child.getNext())
        {
            if (child == a)
            {
                return true;
            }
            if (child.getChild() != null && reachable(a, child.getChild()))
            {
                return true;
            }
        }
        return false;
    }

}
