
INCLUDE_DIRECTORIES(
${BULLET_PHYSICS_SOURCE_DIR}/src
${VECTOR_MATH_INCLUDE}
)



SET(BulletSoftBodyCPUSolvers_SRCS
	btSoftBodySolver_CPU.cpp
)

SET(BulletSoftBodyCPUSolvers_HDRS
	btSoftBodySolver_CPU.h
	btSoftBodySolverData.h
)


ADD_LIBRARY(BulletSoftBodySolvers_CPU  ${BulletSoftBodyCPUSolvers_SRCS} ${BulletSoftBodyCPUSolvers_HDRS} )
SET_TARGET_PROPERTIES(BulletSoftBodySolvers_CPU PROPERTIES VERSION ${BULLET_VERSION})
SET_TARGET_PROPERTIES(BulletSoftBodySolvers_CPU PROPERTIES SOVERSION ${BULLET_VERSION})
IF (BUILD_SHARED_LIBS)
	TARGET_LINK_LIBRARIES(BulletSoftBodySolvers_CPU BulletSoftBody)
ENDIF (BUILD_SHARED_LIBS)

IF (INSTALL_LIBS)
	IF (NOT INTERNAL_CREATE_DISTRIBUTABLE_MSVC_PROJECTFILES)
		IF (${CMAKE_MAJOR_VERSION}.${CMAKE_MINOR_VERSION} GREATER 2.5)
			IF (APPLE AND BUILD_SHARED_LIBS AND FRAMEWORK)
				INSTALL(TARGETS BulletSoftBodySolvers_CPU DESTINATION .)
			ELSE (APPLE AND BUILD_SHARED_LIBS AND FRAMEWORK)
			INSTALL(TARGETS BulletSoftBodySolvers_CPU DESTINATION lib${LIB_SUFFIX})
#headers are already installed by BulletMultiThreaded library
			ENDIF (APPLE AND BUILD_SHARED_LIBS AND FRAMEWORK)
		ENDIF (${CMAKE_MAJOR_VERSION}.${CMAKE_MINOR_VERSION} GREATER 2.5)

		IF (APPLE AND BUILD_SHARED_LIBS AND FRAMEWORK)
			SET_TARGET_PROPERTIES(BulletSoftBodySolvers_CPU PROPERTIES FRAMEWORK true)
			SET_TARGET_PROPERTIES(BulletSoftBodySolvers_CPU PROPERTIES PUBLIC_HEADER "${BulletSoftBodyCPUSolvers_HDRS}")
		ENDIF (APPLE AND BUILD_SHARED_LIBS AND FRAMEWORK)
	ENDIF (NOT INTERNAL_CREATE_DISTRIBUTABLE_MSVC_PROJECTFILES)
ENDIF (INSTALL_LIBS)
