package chain;


import document.Packable;

import java.awt.*;
import java.util.List;

/**
 * Interface {@code Chain} describes dedicated coreference.
 *
 * @author Vadim Baydyuk
 * @see Location
 */

public interface Chain extends Packable {
    /**
     * Set name of chain.
     *
     * @param name the name which should be sated
     */
    void setName(String name);

    /**
     * Get name of the chain.
     *
     * @return name of the chain.
     */
    String getName();

    /**
     * Get id of the chain.
     *
     * @return id of the chain.
     */
    int getId();

    /**
     * Add selected part to chain.
     *
     * @param location the location of this part.
     */
    void addPart(Location location);

    /**
     * Add selected parts to chain.
     *
     * @param locations the locations of parts.
     */
    void addAll(List<Location> locations);

    /**
     * Delete part of the chain.
     *
     * @param location the location op part that should be deleted.
     */
    void deletePart(Location location);

    /**
     * Get color of the chain.
     *
     * @return the color of chain.
     */
    Color getColor();

    /**
     * Set color of the chain
     *
     * @param color the color that should be set.
     */
    void setColor(Color color);

    /**
     * Get locations of all parts of the chain
     *
     * @return locations for all parts of chain
     */
    List<Location> getLocations();
}
