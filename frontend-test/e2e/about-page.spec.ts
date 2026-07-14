import { test, expect } from '@playwright/test';

test.describe('About Page', () => {

  test('about page has correct title', async ({ page }) => {
    await page.goto('/about');
    await expect(page).toHaveTitle(/About/);
  });

  test('about page displays heading', async ({ page }) => {
    await page.goto('/about');
    await expect(page.locator('h1')).toContainText('About ArchieMate');
  });

  test('about page has description paragraphs', async ({ page }) => {
    await page.goto('/about');
    const pageContent = page.locator('main, section');
    await expect(pageContent).toContainText('ArchieMate is a full-stack chatbot');
    await expect(pageContent).toContainText('live streaming platforms');
  });

  test('about page lists technologies', async ({ page }) => {
    await page.goto('/about');
    await expect(page.locator('ul')).toContainText('Scala 3.6.4 & Pekko');
    await expect(page.locator('ul')).toContainText('Scala.js with Laminar');
    await expect(page.locator('ul')).toContainText('Tailwind CSS');
  });

  test('about page has "Read the docs" link', async ({ page }) => {
    await page.goto('/about');
    const docsLink = page.locator('a:has-text("Read the docs")');
    await expect(docsLink).toBeVisible();
    await expect(docsLink).toHaveAttribute('href', '/docs');
  });

  test('navigating to /about shows the about page', async ({ page }) => {
    await page.goto('/about');
    await expect(page.locator('h1')).toContainText('About ArchieMate');
  });
});
