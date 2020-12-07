package com.dilatush.shedsolar;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

/**
 * <p>Implements a filter that removes noise from a series of measurement values.  The filter works by constructing chains of plausibly related data
 * points, then throwing away any data points that aren't part the longest such chain.  This method works for data series that change relatively
 * slowly compared with the sampling interval, such as a series of temperature or humidity values.</p>
 * <p>"Plausibility" of a connection between a new measurement and existing measurements is determined by measuring the "closeness" of the new
 * measurement to all the existing measurements.  The relationship with the smallest closeness value is chosen as the new measurement's ancestor, thus
 * determining which chain it belongs to.  Closeness is computed by the method passed into the constructor.</p>
 */
class NoiseFilter {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private static final boolean LOG_CLOSENESS = true;

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern( "mm:ss.SSS" ).withZone( ZoneId.systemDefault() );

    private final long  depthMS;
    private final Closeness closeness;

    private SampleTreeNode root = null;


    /**
     * Creates a new instance of this class with no samples and the given parameters.  The {@link #depthMS} is how many milliseconds of samples will
     * be retained in the filter.  The more samples that are retained, the better the discrimination between the chain lengths - which translates into
     * better rejection of longer noise bursts.
     *
     * @param _depthMS the number of milliseconds of samples that will be retained in the filter
     * @param _closeness the closeness function for this filter
     */
    public NoiseFilter( final long _depthMS, final Closeness _closeness ) {
        closeness = _closeness;
        depthMS     = _depthMS;
    }


    /**
     * Adds a new sample (with the given measurement reading) to the filter.
     *
     * @param _reading the sample to add
     */
    public void addSample( final MeasurementReading _reading ) {

        // create our shiny new node...
        SampleTreeNode newNode = new SampleTreeNode( _reading );

        // if this is the first sample added, it just becomes the root...
        if( root == null ) {
            root = newNode;
            return;
        }

        // otherwise, traverse the existing sample tree to find closest existing sample, and then link to it...
        CloseNode closestNode = findClosestNode( root, newNode );
        linkNewNode( newNode, closestNode.node );

        // increment the branch size all the way down to the root...
        SampleTreeNode currentNode = newNode;
        while( currentNode.parent != null ) {
            currentNode.parent.branchSize++;
            currentNode = currentNode.parent.thisNode;
        }
    }


    /**
     * Prune any elements in the sample tree that older than the configured depth.  Note that the root is always the oldest element, so the method
     * employed here is to simply prune the root repeatedly until the root doesn't need pruning.
     *
     * @param _now prune relative to this time
     */
    public void prune( final Instant _now ) {

        // sort our links...
        sortFwdLinks( root );

        // while we still have a root that's at least as old as our pruning threshold...
        Instant pruneTo = _now.minusMillis( depthMS );
        while( (root != null) && root.reading.timestamp.isBefore( pruneTo ) ) {

            // if there are no children of the root, null the root and leave...
            if( root.fwdLinks.size() == 0 ) {
                root = null;
                continue;
            }

            // set our new root...
            root = root.fwdLinks.get(0).fwdNode;
        }
    }


    /**
     * <p>Returns the filtered measurement reading, if the sample tree is at least as deep as the given depth parameter; otherwise, returns
     * <code>null</code>.  The given depth must be less than the tree's depth by at least one nominal sampling interval.</p>
     * <p>The measurement reading returned is the one on the longest (most plausible) chain of measurements that is closest to, but earlier than,
     * the current time minus the given noise margin.</p>
     *
     * @param _minDepth the minimum depth of the tree, in milliseconds, before a reading will be returned
     * @param _noiseMargin the noise margin in milliseconds
     * @param _now the time the noise margin is relative to
     * @return the measurement reading found, or <code>null</code> if none was found
     */
    public MeasurementReading measurementAt( final long _minDepth, final long _noiseMargin, final Instant _now ) {

        // sort our links...
        sortFwdLinks( root );

        // if we have no tree yet, return a null...
        if( root == null )
            return null;

        // if our tree isn't deep enough yet, return a null...
        if( _minDepth > (_now.toEpochMilli() - root.reading.timestamp.toEpochMilli()) )
            return null;

        // we have a tree, so follow the longest branch until we find either the end of the branch, or a node with a later timestamp...
        Instant measurementTime = _now.minusMillis( _noiseMargin );
        SampleTreeNode current = root;
        while( (current.fwdLinks.size() > 0) && (current.fwdLinks.get(0).fwdNode.reading.timestamp.isBefore( measurementTime )) ) {
            current = current.fwdLinks.get(0).fwdNode;
        }

        // when we get here, the current node's reading is the one we want, so return it...
        return current.reading;
    }


    /**
     * Returns a string representation of this instance.
     *
     * @return the string representation of this instance
     */
    public String toString() {

        // sort our links...
        sortFwdLinks( root );

        // traverse the sample tree to find the root node of all the branches...
        List<SampleTreeNode> branchRoots = new ArrayList<>();
        branchRoots.add( root );   // add our root in, 'cause findRoots won't get it...
        findRoots( root, branchRoots );
        branchRoots.sort( new Comparator<SampleTreeNode>() {
            @Override
            public int compare( final SampleTreeNode _stn1, final SampleTreeNode _stn2 ) {
                return (int) (_stn2.reading.timestamp.toEpochMilli() - _stn1.reading.timestamp.toEpochMilli());
            }
        } );

        // get our branches into a list (ordered from oldest to youngest root) of lists of branch members (ordered from oldest to youngest)...
        List<BranchDescriptor> branches = new ArrayList<>();
        for( SampleTreeNode branchRoot : branchRoots ) {
            branches.add( new BranchDescriptor( branchRoot, branches.size() ) );
        }

        // get our line descriptors, in temporal order...
        List<LineDescriptor> lines = new ArrayList<>();
        int remainingBranches = branches.size();
        while( remainingBranches > 0 ) {

            // find the oldest reading...
            BranchDescriptor oldestBranch = null;
            for( BranchDescriptor branch : branches ) {
                if( branch.index < branch.members.size() ) {
                    if( (oldestBranch == null) || branch.getIndexedTimestamp().isBefore( oldestBranch.getIndexedTimestamp() ) ) {
                        oldestBranch = branch;
                    }
                }
            }
            assert oldestBranch != null;

            // add a line descriptor for this bad boy...
            lines.add( new LineDescriptor( oldestBranch ) );

            // bump the index on our oldest find...
            oldestBranch.index++;

            // if this was the last member, bump down the number of branches remaining to be exhausted...
            if( oldestBranch.index >= oldestBranch.members.size() )
                remainingBranches--;
        }

        // we're finally ready to start building our result string...
        StringBuilder result = new StringBuilder();
        for( LineDescriptor line : lines ) {

            // first the timestamp -- just minutes, seconds, and milliseconds...
            result.append( TIMESTAMP_FORMATTER.format( line.node.reading.timestamp ) );

            // now we get all our columns...
            for( BranchDescriptor branch : branches ) {

                // are we in our reading's column?
                if( line.branch == branch.branchIndex ) {

                    // output the reading value as xxx.xx, 7 characters...
                    result.append( String.format( " %1$6.2f", line.node.reading.measurement ) );
                }

                // are we in this column's range?
                else if( branch.inRange( line.node.reading.timestamp ) ) {
                    result.append( "   |   " );
                }
                else {
                    result.append( "       " );
                }
            }

            // we'll need a line separator...
            result.append( System.lineSeparator() );
        }

        return result.toString();
    }


    private static class BranchDescriptor {
        private final List<SampleTreeNode> members;
        private final Instant              start;
        private final Instant              end;
        private       int                  index;
        private final int                  branchIndex;

        private BranchDescriptor( final SampleTreeNode _branchRoot, final int _branchIndex ) {
            members = new ArrayList<>();
            findMembers( _branchRoot, members );
            start = members.get( 0 ).reading.timestamp;
            end   = members.get( members.size() - 1 ).reading.timestamp;
            branchIndex = _branchIndex;
            index = 0;
        }


        private boolean inRange( final Instant _time ) {
            return !( _time.isBefore( start ) || _time.isAfter( end ) );
        }


        private Instant getIndexedTimestamp() {
            return members.get( index ).reading.timestamp;
        }
    }


    private static class LineDescriptor {
        private final SampleTreeNode node;
        private final int branch;
        private final int member;
        private final int newBranches;


        private LineDescriptor( final BranchDescriptor _branchDescriptor ) {
            node = _branchDescriptor.members.get( _branchDescriptor.index );
            branch = _branchDescriptor.branchIndex;
            member = _branchDescriptor.index;
            newBranches = node.fwdLinks.size() - 1;
        }
    }


    private static void findMembers( final SampleTreeNode _current, final List<SampleTreeNode> _members ) {
        _members.add( _current );
        if( _current.fwdLinks.size() > 0 )
            findMembers( _current.fwdLinks.get(0).fwdNode, _members );
    }


    private static void findRoots( final SampleTreeNode _node, final List<SampleTreeNode> _roots ) {

        // iterate over all our children...
        int childCount = 0;
        for( FwdLink fwdLink : _node.fwdLinks ) {

            // add our child's branch roots...
            findRoots( fwdLink.fwdNode, _roots );

            // if this is our first child (which is branch zero), then add it as a new branch root...
            childCount++;
            if( childCount > 1 ) {
                _roots.add( fwdLink.fwdNode );
            }
        }
    }


    /**
     * Recursively sorts all the forward links in the given node and its children, into order from the largest branch size to the smallest.
     *
     * @param _node the node to sort
     */
    private void sortFwdLinks( final SampleTreeNode _node ) {
        if( _node.fwdLinks.size() > 1 ) {
            Collections.sort( _node.fwdLinks );
        }
        for( FwdLink link : _node.fwdLinks ) {
            sortFwdLinks( link.fwdNode );
        }
    }


    /**
     * Links the given new node (for a sample being added to the sample tree) to the given closest node (the node representing the existing sample
     * that is most plausibly related to the new sample).
     *
     * @param _newNode     the node for the sample being added
     * @param _closestNode the node for the closest existing sample
     */
    private void linkNewNode( final SampleTreeNode _newNode, final SampleTreeNode _closestNode ) {

        // make a forward link in our parent...
        FwdLink newFwdLink = new FwdLink( _closestNode, _newNode );
        boolean success = _closestNode.fwdLinks.add( newFwdLink );

        // set the parent link in our new child...
        _newNode.parent = newFwdLink;
    }


    /**
     * Recursively traverses the entire sample tree to find the existing sample that is closest to the given new node.
     *
     * @param _current the node currently being examined
     * @param _newNode the new node being compared with existing nodes
     * @return the tuple of the closest node and the closeness value
     */
    private CloseNode findClosestNode( final SampleTreeNode _current, final SampleTreeNode _newNode ) {

        // compute the closeness of the new node to the current node...
        CloseNode closestNode = new CloseNode( _current, closeness.closeness( _newNode.reading, _current.reading ) );

        // now see if any of the current node's children are any closer...
        for( FwdLink childLink : _current.fwdLinks ) {

            closestNode = closest( closestNode, findClosestNode( childLink.fwdNode, _newNode ) );
        }

        // return the closest thing we found...
        return closestNode;
    }


    /**
     * Returns the closer of the two given close nodes.
     *
     * @param _a a close node
     * @param _b another close node
     * @return the closer of the two given close nodes
     */
    private CloseNode closest( final CloseNode _a, final CloseNode _b ) {
        return (_a.closeness < _b.closeness) ? _a : _b;
    }


    /**
     * A simple tuple to hold a node and its degree of closeness.
     */
    private static class CloseNode {
        private final SampleTreeNode node;
        private final float closeness;


        public CloseNode( final SampleTreeNode _node, final float _closeness ) {
            node = _node;
            closeness = _closeness;
        }
    }


    public interface Closeness {

        float closeness( final MeasurementReading _newReading, final MeasurementReading _pastReading );
    }


    /**
     * A tuple representing a single node in the sample tree.
     */
    private static class SampleTreeNode implements Comparable<SampleTreeNode> {
        private final MeasurementReading reading;
        private final List<FwdLink> fwdLinks;
        private FwdLink parent;


        public SampleTreeNode( final MeasurementReading _reading ) {
            reading = _reading;
            fwdLinks = new ArrayList<>();
        }


        @Override
        public int compareTo( final SampleTreeNode _o ) {
            return (int) ((reading.timestamp.toEpochMilli() - _o.reading.timestamp.toEpochMilli()) >> 32);
        }


        @Override
        public boolean equals( final Object _o ) {
            if( this == _o ) return true;
            if( _o == null || getClass() != _o.getClass() ) return false;
            SampleTreeNode that = (SampleTreeNode) _o;
            return reading.equals( that.reading ) && fwdLinks.equals( that.fwdLinks ) && Objects.equals( parent, that.parent );
        }


        @Override
        public int hashCode() {
            return Objects.hash( reading, fwdLinks, parent );
        }
    }


    /**
     * A tuple representing a forward link from one sample tree node to a child node.
     */
    private static class FwdLink implements Comparable<FwdLink> {
        private final SampleTreeNode thisNode;
        private final SampleTreeNode fwdNode;
        private int branchSize;


        public FwdLink( final SampleTreeNode _thisNode, final SampleTreeNode _fwdNode ) {
            thisNode = _thisNode;
            fwdNode = _fwdNode;
            branchSize = 0;
        }


        /**
         * Compare sample nodes in descending order of branch size.
         *
         * @param _fwdLink the sample forward link to compare this instance to
         * @return per the interface's contract, in inverse order of forward link branch size
         */
        @Override
        public int compareTo( final FwdLink _fwdLink ) {
            return _fwdLink.branchSize - branchSize;
        }
    }

    /**
     * A tuple containing a measurement and its timestamp.
     */
    public static class MeasurementReading {
        public final float measurement;
        public final Instant timestamp;


        public MeasurementReading( final float _measurement, final Instant _timestamp ) {
            measurement = _measurement;
            timestamp = _timestamp;
        }


        @Override
        public String toString() {
            return "MeasurementReading { measurement = " + measurement + ", timestamp = " + timestamp + " }";
        }
    }
}
