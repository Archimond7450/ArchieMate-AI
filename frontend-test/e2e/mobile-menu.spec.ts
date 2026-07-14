import { test, expect } from '@playwright/test';

test.describe('Mobile Menu', () => {

  test('hamburger button is visible on mobile viewport', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');
    const hamburgerButton = page.getByRole('button', { name: 'Toggle mobile menu' });
    await expect(hamburgerButton).toBeVisible();
  });

  test('clicking hamburger opens mobile menu', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');
    const hamburgerButton = page.getByRole('button', { name: 'Toggle mobile menu' });

    // Menu should be closed initially (no mobile menu links visible)
    let mobileLinks = await page.locator('div.space-y-1 a').all();
    expect(mobileLinks.length).toBe(0);

    // Click hamburger to open
    await hamburgerButton.click();

    // Menu should now be visible with navigation links
    mobileLinks = await page.locator('div.space-y-1 a').all();
    expect(mobileLinks.length).toBe(3);
  });

  test('clicking hamburger again closes mobile menu', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');
    const hamburgerButton = page.getByRole('button', { name: 'Toggle mobile menu' });

    // Open menu
    await hamburgerButton.click();
    let mobileLinks = await page.locator('div.space-y-1 a').all();
    expect(mobileLinks.length).toBe(3);

    // Close menu
    await hamburgerButton.click();
    mobileLinks = await page.locator('div.space-y-1 a').all();
    expect(mobileLinks.length).toBe(0);
  });

  test('hamburger icon changes to close icon when menu is open', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');
    const hamburgerButton = page.getByRole('button', { name: 'Toggle mobile menu' });
    const svg = hamburgerButton.locator('svg');

    // Initially shows hamburger (three lines)
    await expect(svg).toHaveCount(1);
    let pathD = await hamburgerButton.locator('svg path').getAttribute('d');
    expect(pathD).toContain('M4 6h16');

    // Click to open - should show close (X)
    await hamburgerButton.click();
    pathD = await hamburgerButton.locator('svg path').getAttribute('d');
    expect(pathD).toContain('M6 18L18 6');

    // Click to close - should show hamburger again
    await hamburgerButton.click();
    pathD = await hamburgerButton.locator('svg path').getAttribute('d');
    expect(pathD).toContain('M4 6h16');
  });

  test('mobile menu link navigates to About page', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');
    const hamburgerButton = page.getByRole('button', { name: 'Toggle mobile menu' });
    await hamburgerButton.click();

    // Click the About link directly by its text within the mobile menu
    const aboutLink = page.locator('div.space-y-1 a').filter({ hasText: 'About' }).first();
    await aboutLink.click();
    await expect(page).toHaveURL('/about');
  });

  test('mobile menu link navigates to Docs page', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');
    const hamburgerButton = page.getByRole('button', { name: 'Toggle mobile menu' });
    await hamburgerButton.click();

    // Click the Docs link directly by its text within the mobile menu
    const docsLink = page.locator('div.space-y-1 a').filter({ hasText: 'Docs' }).first();
    await docsLink.click();
    await expect(page).toHaveURL('/docs');
  });

  test('dark mode toggle is visible on mobile viewport', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');
    const darkModeButton = page.getByRole('button', { name: 'Toggle dark mode' });
    await expect(darkModeButton).toBeVisible();
  });

  test('dark mode toggle works from mobile menu open state', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');
    const hamburgerButton = page.getByRole('button', { name: 'Toggle mobile menu' });
    const darkModeButton = page.getByRole('button', { name: 'Toggle dark mode' });

    // Open mobile menu
    await hamburgerButton.click();
    let mobileLinks = await page.locator('div.space-y-1 a').all();
    expect(mobileLinks.length).toBe(3);

    // Toggle dark mode while menu is open
    await darkModeButton.click();
    await expect(page.locator('html')).toHaveClass(/dark/);

    // Menu should still be open
    mobileLinks = await page.locator('div.space-y-1 a').all();
    expect(mobileLinks.length).toBe(3);
  });

  test('mobile menu is accessible via aria-expanded', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');
    const hamburgerButton = page.getByRole('button', { name: 'Toggle mobile menu' });

    // Initially closed
    await expect(hamburgerButton).toHaveAttribute('aria-expanded', 'false');

    // Open
    await hamburgerButton.click();
    await expect(hamburgerButton).toHaveAttribute('aria-expanded', 'true');

    // Close
    await hamburgerButton.click();
    await expect(hamburgerButton).toHaveAttribute('aria-expanded', 'false');
  });
});
