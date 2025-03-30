package org.waterfogsw.kotlinjdslgraalvmsupport.sample

import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service

@Service
class UserService(
    private val userRepository: UserRepository,
) {

    fun searchByName(
        name: String? = null,
        pageable: Pageable,
    ): List<UserEntity> {
        return userRepository.findAll(pageable) {
            select(
                entity(UserEntity::class),
            ).from(
                entity(UserEntity::class),
            ).whereAnd(
                path(UserEntity::name).eq(name),
            )
        }.filterNotNull()
    }
}
