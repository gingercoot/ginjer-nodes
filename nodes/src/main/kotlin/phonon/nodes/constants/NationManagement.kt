/**
 * Enum statuses for nation management functions
 *
 */

package phonon.nodes.constants

public val ErrorNationExists = Exception("Nation name already exists")
public val ErrorTownHasNation = Exception("Town already has a nation")
public val ErrorPlayerHasNation = Exception("Player already has a nation")
public val ErrorPlayerNotInTown = Exception("Player not in this town")
public val ErrorNationDoesNotHaveTown = Exception("Nation does not have town")

public val ErrorTownDoesNotExist = Exception("Town does not exist")
