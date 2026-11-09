from sqlalchemy import create_engine
from sqlalchemy.orm import declarative_base, sessionmaker
from sqlalchemy.pool import StaticPool

from config import settings


DATABASE_URL = settings.DATABASE_URL

engine_options = {
    "pool_pre_ping": True,
}

if DATABASE_URL.startswith("sqlite"):
    engine_options["connect_args"] = {
        "check_same_thread": False
    }

    if DATABASE_URL in (
        "sqlite://",
        "sqlite:///:memory:",
    ):
        engine_options["poolclass"] = StaticPool


engine = create_engine(
    DATABASE_URL,
    **engine_options
)


SessionLocal = sessionmaker(
    autocommit=False,
    autoflush=False,
    bind=engine,
    expire_on_commit=False,
)


Base = declarative_base()


def get_db():
    db = SessionLocal()

    try:
        yield db
    finally:
        db.close()


def create_tables():
    Base.metadata.create_all(bind=engine)


def get_database_session():
    return SessionLocal()


def lock_for_update(query):
    """
    Apply row locking when supported. SQLite does not support SELECT ... FOR
    UPDATE, so return the original query without row locking only for SQLite.
    For other database dialects, use row locking and let errors surface.
    """
    session = getattr(query, "session", None)
    bind = session.get_bind() if session else None
    if bind and bind.dialect.name == "sqlite":
        return query
    return query.with_for_update()


def check_database_connection():
    try:
        with engine.connect() as connection:
            connection.exec_driver_sql("SELECT 1")

        return True

    except Exception as error:
        print(f"Database connection failed: {error}")
        return False
