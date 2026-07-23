import styles from "./loading.module.css";

export default function InvestigationLoading() {
  return (
    <main id="main-content" className={styles.loading} aria-busy="true" aria-label="Loading investigation">
      <div className={styles.header} />
      <div className={styles.layout}>
        <aside />
        <section>
          <span />
          <span />
          <span />
          <span />
        </section>
        <aside />
      </div>
      <p className="sr-only">Loading the authorized investigation projection.</p>
    </main>
  );
}
