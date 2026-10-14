package com.rioikeda.newslisten.architecture.fixture.unknown

import com.rioikeda.newslisten.architecture.fixture.outside.UnknownCollaborator

class UnknownCollaboratorOwner(private val collaborator: UnknownCollaborator) {
    fun ping(): Int = 1
}
