# No rules of our own: the libraries bring the ones they need (kotlinx.serialization, okio) and R8 keeps the rest.
# Every release build must be tried on a TV, because only a shrunk build can lose code that is reached by reflection.
